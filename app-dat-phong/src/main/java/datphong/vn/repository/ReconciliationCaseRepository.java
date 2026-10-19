package datphong.vn.repository;

import datphong.vn.constant.CaseType;
import datphong.vn.exception.entity.ReconciliationCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReconciliationCaseRepository extends JpaRepository<ReconciliationCase, Long> {
    Optional<ReconciliationCase> findFirstByPaymentIdAndTypeAndStatus(Long paymentId, CaseType type, String status);

    Optional<ReconciliationCase> findFirstByRefundIdAndTypeAndStatus(Long refundId, CaseType type, String status);

    Page<ReconciliationCase> findByStatusOrderByIdDesc(String status, Pageable pageable);
}
