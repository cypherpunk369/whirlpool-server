package com.samourai.whirlpool.server.persistence.repositories;

import com.samourai.whirlpool.server.persistence.to.MixOutputTO;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface MixOutputRepository extends CrudRepository<MixOutputTO, Long> {

  List<MixOutputTO> findAllByOrderByCreatedDesc();

  Optional<MixOutputTO> findByAddress(String address);

  Collection<MixOutputTO> deleteByAddress(String addresses);
}
