package datphong.vn.repository;

import datphong.vn.exception.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByScopeAndIdemKey(String scope, String idemKey);
}
