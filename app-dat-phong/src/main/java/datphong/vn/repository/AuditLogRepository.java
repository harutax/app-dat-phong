package datphong.vn.repository;

import datphong.vn.exception.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select a from AuditLog a where (:actorId is null or a.actorId = :actorId) and (:entityType is null or a.entityType = :entityType) "
            + "and (:entityId is null or a.entityId = :entityId) and (:requestId is null or a.requestId = :requestId) "
            + "and a.at >= :from and a.at < :to order by a.at desc, a.id desc")
    Page<AuditLog> search(@Param("actorId") Long actorId, @Param("entityType") String entityType, @Param("entityId") String entityId,
                          @Param("requestId") String requestId, @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
