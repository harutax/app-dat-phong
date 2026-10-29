package datphong.vn.controller;

import datphong.vn.constant.MailStatus;
import datphong.vn.dto.request.AdminRequests;
import datphong.vn.dto.response.AuthResponses;
import datphong.vn.dto.response.PaymentResponses.CaseDto;
import datphong.vn.dto.response.BookingResponses.HostBookingDto;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.entity.Amenity;
import datphong.vn.exception.entity.AuditLog;
import datphong.vn.exception.BusinessException;
import datphong.vn.repository.AmenityRepository;
import datphong.vn.repository.AuditLogRepository;
import datphong.vn.repository.RoleRepository;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** UC-19/20/30/31/34 + SUPPORT. Phân quyền theo permission cụ thể: role ADMIN thiếu permission vẫn 403. */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final UserService users;
    private final ConfigService config;
    private final AuditLogRepository auditRepo;
    private final AmenityRepository amenities;
    private final RoleRepository roles;
    private final SearchVersionService searchVersion;
    private final AuditService audit;
    private final EmailTemplateService templates;
    private final MailAdminService mailAdmin;
    private final ReconciliationService reconciliation;
    private final BookingQueryService bookingQueries;
    private final datphong.vn.service.StayService stay;

    // ---- users / roles
    @GetMapping("/admin/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public Page<AuthResponses.Profile> users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return users.search(q, page, size);
    }

    @PostMapping("/admin/users/{id}/lock")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public Map<String, String> lock(@PathVariable Long id, @Valid @RequestBody AdminRequests.LockUser r) {
        users.setLocked(CurrentUser.id(), id, true, r.reason());
        return Map.of("status", "LOCKED");
    }

    @PostMapping("/admin/users/{id}/unlock")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public Map<String, String> unlock(@PathVariable Long id, @Valid @RequestBody AdminRequests.LockUser r) {
        users.setLocked(CurrentUser.id(), id, false, r.reason());
        return Map.of("status", "ACTIVE");
    }

    /** API riêng cho cấp quyền (tách khỏi sửa hồ sơ). */
    @PutMapping("/admin/users/{id}/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public AuthResponses.Profile roles(@PathVariable Long id, @Valid @RequestBody AdminRequests.SetRoles r) {
        return users.setRoles(CurrentUser.id(), id, r.roles(), r.reason());
    }

    /** UC-20: danh sách role và permission hiện hành (chỉ đọc; cấp/thu quyền qua PUT /admin/users/{id}/roles). */
    @GetMapping("/admin/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles() {
        return roles.findAll().stream().sorted(java.util.Comparator.comparing(r -> r.getCode())).map(r -> Map.<String, Object>of(
                "code", r.getCode(),
                "description", r.getDescription() == null ? "" : r.getDescription(),
                "permissions", r.getPermissions().stream().map(p -> p.getCode()).sorted().toList())).toList();
    }

    // ---- amenities
    /** UC-20: danh mục tiện ích kể cả mục đã vô hiệu hóa (danh mục đang dùng không xóa cứng). */
    @GetMapping("/admin/amenities")
    @PreAuthorize("hasAuthority('AMENITY_MANAGE')")
    public List<Map<String, Object>> listAmenities() {
        return amenities.findAll().stream().sorted(java.util.Comparator.comparing(Amenity::getCode))
                .map(a -> Map.<String, Object>of("code", a.getCode(), "name", a.getName(), "active", a.isActive())).toList();
    }

    @PostMapping("/admin/amenities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('AMENITY_MANAGE')")
    @Transactional
    public Map<String, Object> upsertAmenity(@Valid @RequestBody AdminRequests.AmenityUpsert r) {
        Amenity a = amenities.findByCodeIn(List.of(r.code())).stream().findFirst().orElseGet(Amenity::new);
        a.setCode(r.code());
        a.setName(r.name());
        a.setActive(r.active());                 // không xóa cứng: danh mục đang dùng chỉ bị vô hiệu hóa
        amenities.save(a);
        audit.record("AMENITY_UPSERT", "AMENITY", a.getCode(), null, null, null, null);
        searchVersion.bump();                    // UC-20: làm mới cache danh mục/tìm kiếm đang dùng tiện ích này
        return Map.of("code", a.getCode(), "active", a.isActive());
    }

    // ---- settings (UC-30)
    @GetMapping("/admin/settings")
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG_READ')")
    public List<Map<String, Object>> settings() {
        return config.listAll();
    }

    @PatchMapping("/admin/settings")
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG_WRITE')")
    public Map<String, String> updateSettings(@Valid @RequestBody AdminRequests.UpdateSettings r) {
        config.update(r.values(), r.effectiveAt(), r.reason(), CurrentUser.id());
        return Map.of("status", "OK");
    }

    // ---- audit (UC-19)
    @GetMapping("/admin/audit")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public Page<AuditLog> audit(@RequestParam(required = false) Long actorId, @RequestParam(required = false) String entityType,
                                @RequestParam(required = false) String entityId, @RequestParam(required = false) String requestId,
                                @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Instant f = from == null ? Instant.now().minus(Duration.ofDays(7)) : from;
        Instant t = to == null ? Instant.now().plusSeconds(60) : to;
        if (Duration.between(f, t).toDays() > 90) throw BusinessException.validation("Khoảng tra cứu audit tối đa 90 ngày");
        audit.record("AUDIT_QUERY", "AUDIT", null, null, null, null, Map.of("entityType", String.valueOf(entityType), "entityId", String.valueOf(entityId)));
        return auditRepo.search(actorId, entityType, entityId, requestId, f, t, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    // ---- email templates (UC-31)
    @GetMapping("/admin/email-templates")
    @PreAuthorize("hasAuthority('EMAIL_TEMPLATE_MANAGE')")
    public List<Map<String, Object>> templates() {
        return templates.list();
    }

    public record DraftRequest(@jakarta.validation.constraints.NotBlank String code, String locale,
                               @jakarta.validation.constraints.NotBlank String subjectTemplate,
                               @jakarta.validation.constraints.NotBlank String htmlBody,
                               @jakarta.validation.constraints.NotBlank String textBody,
                               @jakarta.validation.constraints.NotEmpty List<String> allowedVariables) {}

    @PostMapping("/admin/email-templates")
    @PreAuthorize("hasAuthority('EMAIL_TEMPLATE_MANAGE')")
    public Map<String, Object> saveDraft(@Valid @RequestBody DraftRequest r) {
        return templates.saveDraft(r.code(), r.locale() == null ? "vi" : r.locale(),
                new EmailTemplateService.Draft(r.subjectTemplate(), r.htmlBody(), r.textBody(), r.allowedVariables()));
    }

    @GetMapping("/admin/email-templates/{id}/versions/{version}/preview")
    @PreAuthorize("hasAuthority('EMAIL_TEMPLATE_MANAGE')")
    public TemplateRenderer.Rendered preview(@PathVariable Long id, @PathVariable int version) {
        return templates.preview(id, version);
    }

    @PostMapping("/admin/email-templates/{id}/publish")
    @PreAuthorize("hasAuthority('EMAIL_TEMPLATE_MANAGE')")
    public Map<String, Object> publish(@PathVariable Long id, @RequestParam int version) {
        return templates.publish(id, version);
    }

    // ---- mail events (UC-34)
    @GetMapping("/admin/mail-events")
    @PreAuthorize("hasAuthority('MAIL_OPERATE')")
    public Page<Map<String, Object>> mailEvents(@RequestParam(required = false) MailStatus status, @RequestParam(required = false) String type,
                                                @RequestParam(required = false) Long bookingId, @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return mailAdmin.search(CurrentUser.require(), status, type, bookingId, from, to, page, size);
    }

    @GetMapping("/admin/mail-events/{id}")
    @PreAuthorize("hasAuthority('MAIL_OPERATE')")
    public Map<String, Object> mailEvent(@PathVariable Long id) {
        return mailAdmin.detail(CurrentUser.require(), id);
    }

    @PostMapping("/admin/mail-events/{id}/retry")
    @PreAuthorize("hasAuthority('MAIL_OPERATE')")
    public Map<String, Object> retry(@PathVariable Long id, @RequestHeader("Idempotency-Key") String key, @RequestParam(defaultValue = "operator retry") String reason) {
        return mailAdmin.retry(CurrentUser.require(), id, key, reason);
    }

    @GetMapping("/admin/mail-stats")
    @PreAuthorize("hasAuthority('MAIL_OPERATE')")
    public List<Map<String, Object>> mailStats(@RequestParam(defaultValue = "7") int days) {
        return mailAdmin.stats(days);
    }

    // ---- support: đối soát / booking
    @GetMapping("/support/reconciliation-cases")
    @PreAuthorize("hasAuthority('SUPPORT_REFUND_RECONCILE')")
    public Page<CaseDto> cases(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reconciliation.openCases(page, size);
    }

    @PostMapping("/support/reconciliation-cases/{id}/resolve")
    @PreAuthorize("hasAuthority('SUPPORT_REFUND_RECONCILE')")
    public CaseDto resolve(@PathVariable Long id, @Valid @RequestBody BookingRequests.SupportAction r) {
        return reconciliation.resolve(id, CurrentUser.id(), r.reason());
    }

    @PostMapping("/refunds/{id}/reconcile")
    @PreAuthorize("hasAuthority('SUPPORT_REFUND_RECONCILE')")
    public Map<String, String> reconcileRefund(@PathVariable Long id, @Valid @RequestBody BookingRequests.SupportAction r) {
        reconciliation.manualReconcileRefund(id, r.reason());
        return Map.of("status", "RECONCILED");
    }

    /** UC-36: SUPPORT đối soát lại một payment chưa rõ trạng thái (hỏi bank theo idempotencyKey cũ). */
    @PostMapping("/support/payments/{id}/reconcile")
    @PreAuthorize("hasAuthority('SUPPORT_REFUND_RECONCILE')")
    public Map<String, Object> reconcilePayment(@PathVariable Long id, @Valid @RequestBody BookingRequests.SupportAction r) {
        return reconciliation.manualReconcilePayment(id, r.reason());
    }

    /** UC-36: tạo refund bù trừ cho ca đối soát (idempotent theo ca). */
    @PostMapping("/support/reconciliation-cases/{id}/compensate")
    @PreAuthorize("hasAuthority('SUPPORT_REFUND_RECONCILE')")
    public CaseDto compensate(@PathVariable Long id, @Valid @RequestBody BookingRequests.SupportAction r) {
        return reconciliation.compensate(id, CurrentUser.id(), r.reason());
    }

    /** UC-15 ngoại lệ: check-out booking chưa check-in, chỉ SUPPORT có quyền riêng + lý do + audit. */
    @PostMapping("/support/bookings/{id}/force-checkout")
    @PreAuthorize("hasAuthority('SUPPORT_STAY_EXCEPTION')")
    public Map<String, String> forceCheckout(@PathVariable Long id, @Valid @RequestBody BookingRequests.SupportAction r) {
        stay.supportForceCheckout(CurrentUser.id(), id, r.reason());
        return Map.of("status", "COMPLETED");
    }

    @GetMapping("/support/bookings/{id}")
    @PreAuthorize("hasAuthority('BOOKING_VIEW') and hasRole('SUPPORT')")
    public HostBookingDto supportBooking(@PathVariable Long id) {
        audit.record("SUPPORT_BOOKING_VIEW", "BOOKING", id, null, null, null, null);
        return bookingQueries.supportDetail(id);
    }

    /** UC-19: truy vết booking <-> payment <-> refund <-> ca đối soát <-> audit. Lượt truy cập dữ liệu nhạy cảm cũng được audit. */
    @GetMapping("/support/bookings/{id}/trace")
    @PreAuthorize("hasAuthority('AUDIT_READ') and hasAuthority('BOOKING_VIEW')")
    public Map<String, Object> traceBooking(@PathVariable Long id) {
        audit.record("SUPPORT_BOOKING_TRACE", "BOOKING", id, null, null, null, null);
        return bookingQueries.trace(id);
    }
}
