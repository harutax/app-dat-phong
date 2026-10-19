package datphong.vn.repository;

import datphong.vn.exception.entity.ConfigValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigValueRepository extends JpaRepository<ConfigValue, Long> {
    Optional<ConfigValue> findByKeyAndScopeAndScopeId(String key, String scope, String scopeId);
}
