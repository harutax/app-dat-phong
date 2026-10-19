package datphong.vn.repository;

import datphong.vn.exception.entity.Property;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    Optional<Property> findByIdAndOwnerId(Long id, Long ownerId);

    List<Property> findByOwnerIdOrderByIdDesc(Long ownerId);

    @Query("select p from Property p left join fetch p.amenities where p.id = :id")
    Optional<Property> findWithAmenities(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Property p where p.id = :id")
    Optional<Property> lockById(@Param("id") Long id);
}
