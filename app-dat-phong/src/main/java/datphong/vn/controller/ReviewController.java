package datphong.vn.controller;

import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.entity.Review;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviews;

    private static Map<String, Object> view(Review r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("propertyId", r.getPropertyId());
        m.put("rating", r.getRating());
        m.put("comment", r.getComment());
        m.put("status", r.getStatus().name());
        m.put("hostReply", r.getHostReply());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    @PostMapping("/bookings/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REVIEW_CREATE')")
    public Map<String, Object> create(@PathVariable Long id, @Valid @RequestBody BookingRequests.Review req) {
        return view(reviews.create(CurrentUser.id(), id, req));
    }

    /** Công khai: chỉ review PUBLISHED. */
    @GetMapping("/properties/{id}/reviews")
    public Page<Map<String, Object>> list(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviews.publicList(id, page, size).map(ReviewController::view);
    }

    @PostMapping("/host/reviews/{id}/reply")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public Map<String, String> reply(@PathVariable Long id, @Valid @RequestBody BookingRequests.NoShow body) {
        reviews.hostReply(CurrentUser.id(), id, body.reason());
        return Map.of("status", "OK");
    }

    @PostMapping("/host/reviews/{id}/report")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public Map<String, String> report(@PathVariable Long id, @Valid @RequestBody BookingRequests.NoShow body) {
        reviews.hostReport(CurrentUser.id(), id, body.reason());
        return Map.of("status", "REPORTED");
    }

    @GetMapping("/support/reviews")
    @PreAuthorize("hasAuthority('REVIEW_MODERATE')")
    public Page<Map<String, Object>> queue(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviews.moderationQueue(page, size).map(ReviewController::view);
    }

    @PostMapping("/support/reviews/{id}/moderate")
    @PreAuthorize("hasAuthority('REVIEW_MODERATE')")
    public Map<String, String> moderate(@PathVariable Long id, @RequestParam String action, @Valid @RequestBody BookingRequests.NoShow body) {
        reviews.moderate(id, action, body.reason());
        return Map.of("status", "OK");
    }
}
