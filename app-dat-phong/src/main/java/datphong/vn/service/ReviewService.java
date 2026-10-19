package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.ReviewStatus;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.Property;
import datphong.vn.exception.entity.Review;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.*;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** UC-16: đánh giá gắn booking COMPLETED, unique theo booking; HOST chỉ phản hồi (không sửa review gốc); SUPPORT kiểm duyệt có audit. */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Set<String> BLOCKED = Set.of("lừa đảo", "địt", "đm", "vcl", "cc");

    private final ReviewRepository reviews;
    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final SearchVersionService searchVersion;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public Review create(Long customerId, Long bookingId, BookingRequests.Review req) {
        Booking b = bookings.findByIdAndCustomerId(bookingId, customerId).orElseThrow(() -> BusinessException.notFound("Booking"));
        if (b.getStatus() != BookingStatus.COMPLETED) throw new BusinessException(ErrorCode.REVIEW_NOT_ELIGIBLE, "Chỉ đánh giá booking đã hoàn tất lưu trú");
        if (reviews.existsByBookingId(bookingId)) throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS, "Booking đã được đánh giá");
        String comment = Texts.stripHtml(req.comment());
        boolean flagged = comment != null && BLOCKED.stream().anyMatch(w -> comment.toLowerCase().contains(w));
        Review r = new Review();
        r.setBookingId(bookingId);
        r.setPropertyId(b.getPropertyId());      // lấy từ booking, không từ client
        r.setCustomerId(customerId);
        r.setRating(req.rating().shortValue());
        r.setComment(comment);
        r.setStatus(flagged ? ReviewStatus.PENDING_MODERATION : ReviewStatus.PUBLISHED);
        try {
            reviews.saveAndFlush(r);     // hai request cùng lúc: unique(booking_id) chặn bản thứ hai
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS, "Booking đã được đánh giá");
        }
        searchVersion.bump();             // aggregate hiển thị nhất quán eventual
        return r;
    }

    @Transactional
    public void hostReply(Long ownerId, Long reviewId, String text) {
        Review r = reviews.findById(reviewId).orElseThrow(() -> BusinessException.notFound("Đánh giá"));
        properties.findByIdAndOwnerId(r.getPropertyId(), ownerId).orElseThrow(() -> BusinessException.notFound("Đánh giá"));
        if (r.getHostReply() != null) throw BusinessException.conflict("Đã có phản hồi");
        r.setHostReply(Texts.stripHtml(text));
        r.setHostRepliedAt(clock.instant());
    }

    @Transactional
    public void hostReport(Long ownerId, Long reviewId, String reason) {
        Review r = reviews.findById(reviewId).orElseThrow(() -> BusinessException.notFound("Đánh giá"));
        Property p = properties.findByIdAndOwnerId(r.getPropertyId(), ownerId).orElseThrow(() -> BusinessException.notFound("Đánh giá"));
        r.setReported(true);
        audit.record("REVIEW_REPORT", "REVIEW", reviewId, null, null, reason, Map.of("propertyId", p.getId()));
    }

    /** Ẩn/khôi phục: dữ liệu gốc vẫn được lưu để truy vết. */
    @Transactional
    public void moderate(Long reviewId, String action, String note) {
        Review r = reviews.findById(reviewId).orElseThrow(() -> BusinessException.notFound("Đánh giá"));
        ReviewStatus from = r.getStatus();
        ReviewStatus to = switch (action) {
            case "HIDE" -> ReviewStatus.HIDDEN;
            case "RESTORE", "APPROVE" -> ReviewStatus.PUBLISHED;
            default -> throw BusinessException.validation("action phải là HIDE | RESTORE | APPROVE");
        };
        r.setStatus(to);
        r.setModerationNote(note);
        r.setReported(false);
        audit.record("REVIEW_MODERATE", "REVIEW", reviewId, from.name(), to.name(), note, null);
        searchVersion.bump();
    }

    @Transactional(readOnly = true)
    public Page<Review> publicList(Long propertyId, int page, int size) {
        return reviews.findByPropertyIdAndStatusOrderByIdDesc(propertyId, ReviewStatus.PUBLISHED, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }

    @Transactional(readOnly = true)
    public Page<Review> moderationQueue(int page, int size) {
        return reviews.findByReportedTrueOrStatusOrderByIdDesc(ReviewStatus.PENDING_MODERATION, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }
}
