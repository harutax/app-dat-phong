package datphong.vn.repository;

import datphong.vn.constant.PaymentStatus;
import datphong.vn.exception.entity.Payment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findFirstByBookingIdOrderByIdDesc(Long bookingId);

    Optional<Payment> findByIdempotencyKey(String key);

    Optional<Payment> findByProviderTxnId(String txnId);

    Optional<Payment> findByIdAndCustomerId(Long id, Long customerId);

    List<Payment> findByBookingIdOrderByIdDesc(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    @Query("select p.id from Payment p where p.status in :statuses and p.updatedAt < :before order by p.updatedAt")
    List<Long> findStaleIds(@Param("statuses") Collection<PaymentStatus> statuses, @Param("before") Instant before,
                            org.springframework.data.domain.Pageable page);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.bookingId = :bookingId and p.status = datphong.vn.constant.PaymentStatus.SUCCEEDED")
    BigDecimal capturedForBooking(@Param("bookingId") Long bookingId);
}
