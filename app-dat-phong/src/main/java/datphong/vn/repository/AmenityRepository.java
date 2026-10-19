package datphong.vn.repository;

import datphong.vn.exception.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AmenityRepository extends JpaRepository<Amenity, Long> {
    List<Amenity> findByCodeIn(Collection<String> codes);

    List<Amenity> findByActiveTrueOrderByName();
}
