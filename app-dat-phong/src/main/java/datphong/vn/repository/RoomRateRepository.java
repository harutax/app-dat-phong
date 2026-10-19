package datphong.vn.repository;

import datphong.vn.exception.entity.RoomRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RoomRateRepository extends JpaRepository<RoomRate, Long> {

    /** Khoảng nửa mở [from, to). */
    @Query("select r from RoomRate r where r.roomTypeId = :typeId and r.stayDate >= :from and r.stayDate < :to order by r.stayDate")
    List<RoomRate> findRange(@Param("typeId") Long typeId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(r) from RoomRate r where r.roomTypeId = :typeId and r.stayDate >= :from")
    long countFrom(@Param("typeId") Long typeId, @Param("from") LocalDate from);
}
