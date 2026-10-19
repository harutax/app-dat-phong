package datphong.vn.repository;

import datphong.vn.constant.CheckinRequestStatus;
import datphong.vn.exception.entity.CheckinRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CheckinRequestRepository extends JpaRepository<CheckinRequest, Long> {

    Optional<CheckinRequest> findFirstByBookingIdAndStatusInOrderByIdDesc(Long bookingId, Collection<CheckinRequestStatus> statuses);

    List<CheckinRequest> findByBookingIdOrderByIdDesc(Long bookingId);

    /** Booking bị hủy/hết hạn/check-in: tự đóng mọi request còn mở. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CheckinRequest c set c.status = datphong.vn.constant.CheckinRequestStatus.CLOSED where c.bookingId = :bid "
            + "and c.status in (datphong.vn.constant.CheckinRequestStatus.PENDING, datphong.vn.constant.CheckinRequestStatus.COUNTER_PROPOSED)")
    int closeOpenForBooking(@Param("bid") Long bookingId);
}
