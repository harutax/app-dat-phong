package datphong.vn.repository;

import datphong.vn.exception.entity.ConfigChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long> {
}
