package datphong.vn.repository;

import datphong.vn.exception.entity.Room;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByPropertyIdOrderByRoomCode(Long propertyId);

    boolean existsByPropertyIdAndRoomCode(Long propertyId, String roomCode);

    @Query("select count(r) from Room r where r.propertyId = :pid and r.status = datphong.vn.constant.RoomStatus.ACTIVE")
    long countActive(@Param("pid") Long propertyId);

    /**
     * Khóa hàng phòng (SELECT ... FOR UPDATE) - cơ chế tuần tự hóa việc giữ chỗ cùng một phòng.
     * Timeout chờ lock 5s (ném PessimisticLockException/CannotAcquireLock -> facade retry có giới hạn).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from Room r where r.id = :id")
    Optional<Room> lockById(@Param("id") Long id);
}
