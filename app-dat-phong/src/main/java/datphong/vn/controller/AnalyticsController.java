package datphong.vn.controller;

import datphong.vn.security.CurrentUser;
import datphong.vn.service.AnalyticsService;
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

    /** Alias theo đặc tả: GET /host/reports -> overview theo property. */
    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) Long propertyId) {
        return analytics.overview(CurrentUser.require(), from, to, propertyId, true);
    }
}
