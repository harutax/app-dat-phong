package datphong.vn.repository;

import datphong.vn.exception.entity.Refund;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByRefundKey(String key);

    List<Refund> findByBookingIdOrderByIdDesc(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> lockById(@Param("id") Long id);

    /** Tổng hoàn đã thành công + đang xử lý/chờ (không tính FAILED đã bỏ) của một payment. */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId and r.status in (datphong.vn.constant.RefundStatus.REQUESTED, datphong.vn.constant.RefundStatus.PROCESSING, datphong.vn.constant.RefundStatus.SUCCEEDED)")
    BigDecimal committedForPayment(@Param("paymentId") Long paymentId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.bookingId = :bookingId and r.status = datphong.vn.constant.RefundStatus.SUCCEEDED")
    BigDecimal succeededForBooking(@Param("bookingId") Long bookingId);

    @Query("select r.id from Refund r where (r.status = datphong.vn.constant.RefundStatus.REQUESTED or (r.status = datphong.vn.constant.RefundStatus.FAILED and r.nextAttemptAt is not null and r.nextAttemptAt <= :now)) order by r.id")
    List<Long> findDispatchable(@Param("now") Instant now, org.springframework.data.domain.Pageable page);

    @Query("select r.id from Refund r where r.status = datphong.vn.constant.RefundStatus.PROCESSING and r.updatedAt < :before order by r.updatedAt")
    List<Long> findStuckProcessing(@Param("before") Instant before, org.springframework.data.domain.Pageable page);

    @Query("select count(r) > 0 from Refund r where r.bookingId = :bookingId and r.status in (datphong.vn.constant.RefundStatus.REQUESTED, datphong.vn.constant.RefundStatus.PROCESSING, datphong.vn.constant.RefundStatus.FAILED)")
    boolean existsOpenForBooking(@Param("bookingId") Long bookingId);
}
