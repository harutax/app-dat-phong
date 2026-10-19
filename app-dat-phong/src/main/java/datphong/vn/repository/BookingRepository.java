package datphong.vn.repository;

import datphong.vn.exception.entity.Booking;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    boolean existsByBookingCode(String code);

    Optional<Booking> findByIdAndCustomerId(Long id, Long customerId);

    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    /** Khóa booking - THỨ TỰ KHÓA THỐNG NHẤT: booking -> payment -> allocation (xem README/ADR). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") Long id);

    @Query(value = "select id from bookings where status = 'PENDING_PAYMENT' and expires_at <= :now order by expires_at limit :limit", nativeQuery = true)
    List<Long> findExpiredIds(@Param("now") Instant now, @Param("limit") int limit);

    /** Claim một booking hết hạn: SKIP LOCKED để nhiều scheduler instance không xử lý trùng và không chặn nhau. */
    @Query(value = "select * from bookings where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now for update skip locked", nativeQuery = true)
    Optional<Booking> claimExpired(@Param("id") Long id, @Param("now") Instant now);

    @Query("select b from Booking b where b.status = datphong.vn.constant.BookingStatus.CONFIRMED and b.checkIn between :from and :to order by b.checkIn, b.id")
    List<Booking> findConfirmedCheckInBetween(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to,
                                              Pageable pageable);
}
