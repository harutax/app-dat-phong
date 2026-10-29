package datphong.vn.controller;

import datphong.vn.security.CurrentUser;
import datphong.vn.exception.BusinessException;
import datphong.vn.service.AnalyticsService;
import datphong.vn.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** HOST/ADMIN. Data scope được ép trong SQL và trong khóa cache - ẩn nút ở frontend không phải biện pháp bảo vệ. */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final AuditService audit;

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) Long propertyId,
                                        @RequestParam(defaultValue = "false") boolean comparePrevious) {
        return analytics.overview(CurrentUser.require(), from, to, propertyId, comparePrevious);
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody AnalyticsService.QueryRequest r) {
        return analytics.query(CurrentUser.require(), r);
    }

    @GetMapping("/occupancy")
    public Map<String, Object> occupancy(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long propertyId) {
        return analytics.occupancy(CurrentUser.require(), from, to, propertyId);
    }

    @GetMapping("/funnel")
    public Map<String, Object> funnel(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) Long propertyId) {
        return analytics.funnel(CurrentUser.require(), from, to, propertyId);
    }

    /** UC-23: dòng tiền - captured, refund succeeded/pending/failed, giao dịch đang xử lý, ca đối soát mở. */
    @GetMapping("/cashflow")
    public Map<String, Object> cashflow(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) Long propertyId) {
        return analytics.cashflow(CurrentUser.require(), from, to, propertyId);
    }

    /**
     * UC-26: quản trị cache thống kê theo quyền - tăng version để mọi khóa cũ không còn được đọc; bắt buộc lý do và có audit.
     * Cần thêm quyền ANALYTICS_CACHE_MANAGE (mặc định chỉ ADMIN), không chỉ ANALYTICS_VIEW.
     */
    @PostMapping("/cache/invalidate")
    @PreAuthorize("hasAuthority('ANALYTICS_CACHE_MANAGE')")
    public Map<String, Object> invalidateCache(@RequestParam String reason) {
        if (reason == null || reason.isBlank()) throw BusinessException.validation("Cần nêu lý do");
        long version = analytics.invalidateCache();
        audit.record("ANALYTICS_CACHE_INVALIDATE", "ANALYTICS_CACHE", version, null, null, reason, null);
        return Map.of("cacheVersion", version);
    }

    /** Alias theo đặc tả: GET /host/reports -> overview theo property. */
    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) Long propertyId) {
        return analytics.overview(CurrentUser.require(), from, to, propertyId, true);
    }
}
