package datphong.vn.repository;

import datphong.vn.constant.MediaStatus;
import datphong.vn.exception.entity.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    long countByPropertyIdAndStatusIn(Long propertyId, Collection<MediaStatus> statuses);

    List<MediaAsset> findByPropertyIdAndStatusOrderBySortOrderAscIdAsc(Long propertyId, MediaStatus status);

    boolean existsByPropertyIdAndStatusAndCoverTrue(Long propertyId, MediaStatus status);

    @Query("select m from MediaAsset m where m.status = datphong.vn.constant.MediaStatus.PENDING and m.expiresAt < :now")
    List<MediaAsset> findExpiredPending(@Param("now") Instant now);

    List<MediaAsset> findTop100ByStatusIn(Collection<MediaStatus> statuses);

    @Query("select m from MediaAsset m where m.propertyId in :ids and m.cover = true and m.status = datphong.vn.constant.MediaStatus.READY")
    List<MediaAsset> findCovers(@Param("ids") Collection<Long> propertyIds);
}
