package com.samourai.whirlpool.server.services;

import com.samourai.javaserver.exceptions.NotifiableException;
import com.samourai.wallet.util.MessageSignUtilGeneric;
import com.samourai.whirlpool.server.beans.Pool;
import com.samourai.whirlpool.server.beans.PoolFee;
import com.samourai.whirlpool.server.beans.Tx0Validation;
import com.samourai.whirlpool.server.beans.rpc.RpcTransaction;
import com.samourai.whirlpool.server.beans.rpc.TxOutPoint;
import com.samourai.whirlpool.server.config.WhirlpoolServerConfig;
import com.samourai.whirlpool.server.exceptions.IllegalInputException;
import com.samourai.whirlpool.server.exceptions.ServerErrorCode;
import com.samourai.whirlpool.server.services.fee.WhirlpoolFeeData;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.samourai.whirlpool.server.services.rpc.RpcClientService;
import com.samourai.whirlpool.server.services.rpc.RpcRawTransactionResponse;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InputValidationService {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private Tx0ValidationService tx0ValidationService;
  private WhirlpoolServerConfig whirlpoolServerConfig;
  private CryptoService cryptoService;
  private MessageSignUtilGeneric messageSignUtil;
  private RpcClientService rpcClientService;
  private PoolService poolService;

  private static final String POOL_50M = "0.5btc";
  private static final String POOL_5M = "0.5btc";
  private static final String POOL_1M = "0.01btc";
  private static final String POOL_100K = "0.001btc";

  public InputValidationService(
      Tx0ValidationService tx0ValidationService,
      WhirlpoolServerConfig whirlpoolServerConfig,
      CryptoService cryptoService,
      MessageSignUtilGeneric messageSignUtil,
      RpcClientService rpcClientService,
      PoolService poolService) {
    this.tx0ValidationService = tx0ValidationService;
    this.whirlpoolServerConfig = whirlpoolServerConfig;
    this.cryptoService = cryptoService;
    this.messageSignUtil = messageSignUtil;
    this.rpcClientService = rpcClientService;
    this.poolService = poolService;
  }

  public void validateProvenance(
      RpcTransaction tx, boolean liquidity, Pool pool, boolean hasMixTxid)
      throws NotifiableException {

    // provenance verification can be disabled with testMode
    if (whirlpoolServerConfig.isTestMode()) {
      log.warn("tx0 check disabled by testMode");
      return; // valid
    }

    // verify input comes from a valid tx0 or previous mix
    boolean isLiquidity =
        checkInputProvenance(tx.getTx(), tx.getTxTime(), pool.getPoolFee(), hasMixTxid);
    if (!isLiquidity && liquidity) {
      throw new IllegalInputException(
          ServerErrorCode.INPUT_REJECTED, "Input rejected: joined as liquidity but is a mustMix");
    }
    if (isLiquidity && !liquidity) {
      throw new IllegalInputException(
          ServerErrorCode.INPUT_REJECTED,
          "Input rejected: joined as mustMix but is as a liquidity");
    }
    return; // valid
  }

  protected boolean checkInputProvenance(
      Transaction tx, long txTime, PoolFee poolFee, boolean hasMixTxid) throws NotifiableException {
    // is it a tx0?
    WhirlpoolFeeData feeData;
    try {
      feeData = tx0ValidationService.decodeFeeData(tx);
    } catch (Exception e) {
      // this is not a tx0 => liquidity coming from a previous whirlpool tx
      if (log.isTraceEnabled()) {
        log.trace("Validating input: txid=" + tx.getHashAsString() + ": feeData=null");
      }

      if (!hasMixTxid) { // not a whirlpool tx
        log.error("Input rejected (not a premix or whirlpool input)", e);
        throw new IllegalInputException(
            ServerErrorCode.INPUT_REJECTED, "Input rejected (not a premix or whirlpool input)");
      }
      return true; // liquidity
    }

    // this is a tx0 => mustMix
    if (log.isTraceEnabled()) {
      log.trace("Validating input: txid=" + tx.getHashAsString() + ", feeData={" + feeData + "}");
    }

    try {
      // verify tx0
      Tx0Validation tx0Validation = tx0ValidationService.validate(tx, txTime, poolFee, feeData);

//      validateTx0Cascading(tx, txTime); // temp

      WhirlpoolServerConfig.ScodeSamouraiFeeConfig scodeConfig = tx0Validation.getScodeConfig();
      if (scodeConfig != null && scodeConfig.isCascading()) { // maybe move to seperate try/catch to catch exception specific to cascading
        // check tx0 cascading
        validateTx0Cascading(tx, txTime);
      }
      return false; // mustMix
    } catch (Exception e) {
      // invalid fees
      log.error(
          "Input rejected (invalid fee for tx0="
              + tx.getHashAsString()
              + ", x="
              + feeData.getFeeIndice()
              + ", feeData={"
              + feeData
              + "}");
      throw new IllegalInputException(
          ServerErrorCode.INPUT_REJECTED,
          "Input rejected (invalid fee for tx0="
              + tx.getHashAsString()
              + ", x="
              + feeData.getFeeIndice()
              + ", scodePayload="
              + (feeData.getScodePayload() != FeePayloadService.SCODE_PAYLOAD_NONE ? "yes" : "no")
              + ")");
    }
  }

  protected void validateTx0Cascading(Transaction tx, long txTime) throws Exception {
    /*
    1. make sure that all inputs come from the same previous TXID (= it's the previous cascading TX0) or throw
       (might not work, should only have 1 input: the change from previous tx0)
    2. get this TX as RpcTransaction using rpcClientService.getRawTransaction() (parent tx or current tx?)
    3. figure out which pool it was for, by checking tx outputs (parent tx or current tx?)
    4. make sure it's a valid tx0 using isValidTx0() (parent tx or current tx?)
    */

    // cascading tx0 should only have 1 input (previous tx0 change)
    List<TransactionInput> txInputs = tx.getInputs();
    if (txInputs.size() != 1) {
      log.error(
          "Input rejected (invalid cascading for tx0="
              + tx.getHashAsString()
              + ")");
      throw new IllegalInputException(
          ServerErrorCode.INPUT_REJECTED,
          "Input rejected (invalid cascading for tx0="
              + tx.getHashAsString()
              + ")");
    }

    // need to determine if comes from a previous tx0

    // RPC of current tx or parent tx?
    RpcTransaction rpcTransaction = new RpcTransaction(
        rpcClientService.getRawTransaction(tx.getHashAsString()).get(),
        cryptoService.getNetworkParameters());

//    RpcTransaction parentRpcTransaction = new RpcTransaction(
//        rpcClientService.getRawTransaction(tx.getInputs().get(0).getParentTransaction().getHashAsString()).get(),
//        cryptoService.getNetworkParameters());


    // get Pool of current tx or parent tx?
    // Maybe handle this part better
    long mixValue = tx.getOutputs().get(3).getValue().getValue(); // assumes 4th output is utxo to be mixed (might have to adjust this, might be 3rd)
//    long mixValue = tx.getInputs().get(0).getParentTransaction().getOutputs().get(3).getValue().getValue(); // Parent Pool ?
    Pool pool;
    if (mixValue > 50000000) {
      pool = poolService.getPool(POOL_50M);
    } else if (mixValue > 5000000) {
      pool = poolService.getPool(POOL_5M);
    } else if (mixValue > 1000000) {
      pool = poolService.getPool(POOL_1M);
    } else if (mixValue > 100000) {
      pool = poolService.getPool(POOL_100K);
    } else {
      throw new IllegalInputException(
          ServerErrorCode.INPUT_REJECTED,
          "Input rejected (Pool size not found for tx0="
              + tx.getHashAsString()
              + ")");
    }

    isValidTx0(rpcTransaction, pool); // this might cause a loop for calling checkInputProvenance() again
//    isValidTx0(parentRpcTransaction, pool); // isValid() of with current tx rpc or parent tx rpc?
  }

  protected boolean isValidTx0(RpcTransaction tx, Pool pool) throws Exception {
    boolean hasMixTxid = false; // it's not a MIX tx
    boolean isLiquidity = checkInputProvenance(tx.getTx(), tx.getTxTime(), pool.getPoolFee(), hasMixTxid); // need to make sure doesn't get stuck in loop
    return !isLiquidity; // not a MIX
  }

  public ECKey validateSignature(TxOutPoint txOutPoint, String message, String signature)
      throws IllegalInputException {
    if (log.isTraceEnabled()) {
      log.trace(
          "Verifying signature: "
              + signature
              + "\n  for address: "
              + txOutPoint.getToAddress()
              + "\n  for message: "
              + message);
    }

    // verify signature of message for address
    if (!messageSignUtil.verifySignedMessage(
        txOutPoint.getToAddress(), message, signature, cryptoService.getNetworkParameters())) {
      log.warn(
          "Invalid signature: verifySignedMessage() failed for input="
              + txOutPoint.toKey()
              + ", message="
              + message
              + ", signature="
              + signature
              + ", address="
              + txOutPoint.getToAddress());
      throw new IllegalInputException(ServerErrorCode.INVALID_ARGUMENT, "Invalid signature");
    }

    ECKey pubkey = messageSignUtil.signedMessageToKey(message, signature);
    if (pubkey == null) {
      log.warn(
          "Invalid signature: signedMessageToKey() failed for input="
              + txOutPoint.toKey()
              + ", message="
              + message
              + ", signature="
              + signature);
      throw new IllegalInputException(ServerErrorCode.INVALID_ARGUMENT, "Invalid signature");
    }
    return pubkey;
  }
}
