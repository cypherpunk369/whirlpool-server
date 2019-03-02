package com.samourai.whirlpool.server.config.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.samourai.whirlpool.protocol.WhirlpoolEndpoint;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.server.services.WebSocketSessionService;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.converter.DefaultContentTypeResolver;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.HandlerMethodReturnValueHandler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurationSupport;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/** Websocket configuration with STOMP. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig extends WebSocketMessageBrokerConfigurationSupport
    implements WebSocketMessageBrokerConfigurer {
  private static Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final int HEARTBEAT_DELAY = 20000;

  public static String[] WEBSOCKET_ENDPOINTS =
      new String[] {
        WhirlpoolEndpoint.WS_CONNECT,
        WhirlpoolEndpoint.WS_REGISTER_INPUT,
        WhirlpoolEndpoint.WS_CONFIRM_INPUT,
        WhirlpoolEndpoint.WS_REVEAL_OUTPUT,
        WhirlpoolEndpoint.WS_SIGNING
      };

  @Autowired private ObjectMapper objectMapper;

  @Autowired private WhirlpoolProtocol whirlpoolProtocol;

  private WebSocketHandler webSocketHandler;

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
    super.configureWebSocketTransport(registry);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    super.configureClientInboundChannel(registration);
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    super.configureClientOutboundChannel(registration);
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
    super.addArgumentResolvers(argumentResolvers);
  }

  @Override
  public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> returnValueHandlers) {
    super.addReturnValueHandlers(returnValueHandlers);
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint(WEBSOCKET_ENDPOINTS)
        // assign a random username as principal for each websocket client
        // this is needed to be able to communicate with a specific client
        .setHandshakeHandler(new AssignPrincipalWebsocketHandler())
        .setAllowedOrigins("*");
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // enable heartbeat (mandatory to detect client disconnect)
    ThreadPoolTaskScheduler te = new ThreadPoolTaskScheduler();
    te.setPoolSize(1);
    te.setThreadNamePrefix("wss-heartbeat-thread-");
    te.initialize();

    registry
        .enableSimpleBroker(whirlpoolProtocol.WS_PREFIX_USER_REPLY)
        .setHeartbeatValue(new long[] {HEARTBEAT_DELAY, HEARTBEAT_DELAY})
        .setTaskScheduler(te);
    registry.setUserDestinationPrefix(whirlpoolProtocol.WS_PREFIX_USER_PRIVATE);
  }

  @Override
  public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
    DefaultContentTypeResolver resolver = new DefaultContentTypeResolver();
    resolver.setDefaultMimeType(MimeTypeUtils.APPLICATION_JSON);
    MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
    converter.setObjectMapper(objectMapper);
    converter.setContentTypeResolver(resolver);
    messageConverters.add(converter);
    return false;
  }

  // listeners for logging purpose

  @EventListener
  public void handleSubscribeEvent(SessionSubscribeEvent event) {
    if (log.isDebugEnabled()) {
      log.debug("[event] subscribe: username=" + event.getUser().getName() + ", event=" + event);
    }
  }

  @EventListener
  public void handleConnectEvent(SessionConnectEvent event) {
    if (log.isDebugEnabled()) {
      log.debug("[event] connect: username=" + event.getUser().getName() + ", event=" + event);
    }
  }

  @EventListener
  public void handleDisconnectEvent(SessionDisconnectEvent event) {
    if (log.isDebugEnabled()) {
      log.debug("[event] disconnect: username=" + event.getUser().getName() + ", event=" + event);
    }
  }

  @Bean
  @Override
  public org.springframework.web.socket.WebSocketHandler subProtocolWebSocketHandler() {
    return getWebSocketHandler();
  }

  private WebSocketHandler getWebSocketHandler() {
    if (this.webSocketHandler == null) {
      this.webSocketHandler = new WebSocketHandler(this);
    }
    return this.webSocketHandler;
  }

  // avoids circular reference
  public void __setWebSocketHandlerListener(WebSocketSessionService webSocketSessionService) {
    getWebSocketHandler().__setWebSocketSessionService(webSocketSessionService);
  }
}
