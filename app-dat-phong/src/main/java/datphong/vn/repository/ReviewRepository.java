package datphong.vn.repository;

import datphong.vn.constant.ReviewStatus;
import datphong.vn.exception.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByBookingId(Long bookingId);

    Page<Review> findByPropertyIdAndStatusOrderByIdDesc(Long propertyId, ReviewStatus status, Pageable pageable);

    Page<Review> findByReportedTrueOrStatusOrderByIdDesc(ReviewStatus status, Pageable pageable);

    Optional<Review> findByBookingId(Long bookingId);
}
