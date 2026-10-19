package datphong.vn.controller;

import datphong.vn.constant.BookingStatus;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.dto.response.BookingResponses.*;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.BookingQueryService;
import datphong.vn.service.StayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** HOST: owner scope được ép trong service/query. Không có endpoint sửa tiền hay trạng thái thanh toán. */
@RestController
@RequestMapping("/host/bookings")
@RequiredArgsConstructor
public class HostBookingController {

    private final BookingQueryService queries;
    private final StayService stay;

    @GetMapping
    @PreAuthorize("hasAuthority('BOOKING_VIEW')")
    public Page<BookingDto> list(@RequestParam(required = false) Long propertyId, @RequestParam(required = false) BookingStatus status,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.hostList(CurrentUser.id(), propertyId, status, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BOOKING_VIEW')")
    public HostBookingDto get(@PathVariable Long id) {
        return queries.hostDetail(CurrentUser.id(), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public Map<String, String> update(@PathVariable Long id, @Valid @RequestBody BookingRequests.HostUpdate req) {
        stay.updateRequest(CurrentUser.id(), id, req);
        return Map.of("status", "OK");
    }

    @PostMapping("/{id}/checkin")
    @PreAuthorize("hasAuthority('STAY_MANAGE')")
    public Map<String, String> checkIn(@PathVariable Long id) {
        stay.checkIn(CurrentUser.id(), id);
        return Map.of("status", "CHECKED_IN");
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasAuthority('STAY_MANAGE')")
    public Map<String, String> checkOut(@PathVariable Long id) {
        stay.checkOut(CurrentUser.id(), id);
        return Map.of("status", "COMPLETED");
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('STAY_MANAGE')")
    public Map<String, String> noShow(@PathVariable Long id, @Valid @RequestBody BookingRequests.NoShow req) {
        stay.noShow(CurrentUser.id(), id, req.reason());
        return Map.of("status", "NO_SHOW");
    }
}
