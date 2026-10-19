package datphong.vn.controller;

import datphong.vn.dto.request.BookingRequests;
import datphong.vn.dto.response.BookingResponses.*;
import datphong.vn.security.AuthUser;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.BookingFacade;
import datphong.vn.service.BookingQueryService;
import datphong.vn.service.CancellationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingFacade facade;
    private final BookingQueryService queries;
    private final CancellationService cancellation;

    /** 201 khi tạo mới; 200 khi là kết quả cũ của cùng idempotencyKey. */
    @PostMapping
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public ResponseEntity<BookingDto> create(@Valid @RequestBody BookingRequests.Create req) {
        var out = facade.create(CurrentUser.id(), req);
        return out.replayed() ? ResponseEntity.ok(out.booking()) : ResponseEntity.status(201).body(out.booking());
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public Page<BookingDto> mine(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.myBookings(CurrentUser.id(), page, size);
    }

    /** CUSTOMER xem của mình; HOST/SUPPORT xem qua /host/bookings và /support/bookings để có data-scope riêng. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public BookingDto get(@PathVariable Long id) {
        return queries.customerView(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/cancel-preview")
    public CancelPreview preview(@PathVariable Long id) {
        return cancellation.preview(CurrentUser.require(), id);
    }

    @PostMapping("/{id}/cancel")
    public CancelResult cancel(@PathVariable Long id, @Valid @RequestBody BookingRequests.Cancel req) {
        AuthUser u = CurrentUser.require();
        return cancellation.cancel(u, id, req);
    }
}
