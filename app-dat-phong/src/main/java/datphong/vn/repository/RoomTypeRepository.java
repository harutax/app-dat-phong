package datphong.vn.repository;

import datphong.vn.exception.entity.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    List<RoomType> findByPropertyIdOrderById(Long propertyId);

    Optional<RoomType> findByIdAndPropertyId(Long id, Long propertyId);

    @Query("select distinct rt from RoomType rt left join fetch rt.amenities where rt.propertyId = :pid order by rt.id")
    List<RoomType> findWithAmenities(@Param("pid") Long propertyId);
}
