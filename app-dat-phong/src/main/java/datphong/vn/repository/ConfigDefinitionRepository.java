package datphong.vn.repository;

import datphong.vn.exception.entity.ConfigDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigDefinitionRepository extends JpaRepository<ConfigDefinition, Long> {
    Optional<ConfigDefinition> findByKey(String key);
}
