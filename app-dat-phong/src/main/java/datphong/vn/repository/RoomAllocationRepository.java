package datphong.vn.repository;

import datphong.vn.exception.entity.RoomAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

public interface RoomAllocationRepository extends JpaRepository<RoomAllocation, Long> {

    Optional<RoomAllocation> findByBookingId(Long bookingId);

    /** Có allocation active giao khoảng [in, out) của phòng này không (gọi khi đã giữ khóa hàng phòng). */
    @Query(value = "select count(*) > 0 from room_allocations where room_id = :roomId and active "
            + "and stay_range && daterange(:in, :out, '[)')", nativeQuery = true)
    boolean existsOverlap(@Param("roomId") Long roomId, @Param("in") LocalDate in, @Param("out") LocalDate out);

    /** Phòng còn chiếm dụng trong tương lai (đóng bán/bảo trì phải bị chặn). */
    @Query(value = "select count(*) > 0 from room_allocations where room_id = :roomId and active and check_out > :today", nativeQuery = true)
    boolean existsActiveFuture(@Param("roomId") Long roomId, @Param("today") LocalDate today);

    /** Allocation active tương lai của loại phòng có số khách vượt capacity mới (chặn giảm sức chứa sai). */
    @Query(value = "select count(*) > 0 from room_allocations a join bookings b on b.id = a.booking_id "
            + "where a.active and a.check_out > :today and b.room_type_id = :roomTypeId and b.guest_count > :capacity", nativeQuery = true)
    boolean existsActiveFutureOverCapacity(@Param("roomTypeId") Long roomTypeId, @Param("capacity") int capacity, @Param("today") LocalDate today);

    /** Giải phóng ĐÚNG MỘT LẦN: UPDATE có điều kiện active = true; trả số hàng đã đổi (0 nếu đã giải phóng). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RoomAllocation a set a.active = false, a.releasedAt = :now where a.bookingId = :bookingId and a.active = true")
    int release(@Param("bookingId") Long bookingId, @Param("now") Instant now);
}
