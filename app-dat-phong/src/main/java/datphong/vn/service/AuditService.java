package datphong.vn.service;

import datphong.vn.exception.entity.AuditLog;
import datphong.vn.repository.AuditLogRepository;
import datphong.vn.security.CurrentUser;
import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Audit append-only.
 * - record(): cùng transaction với thay đổi nghiệp vụ -> chỉ tồn tại khi thay đổi thật sự commit (outcome SUCCESS).
 * - attempt(): REQUIRES_NEW, ghi nhận "đã thử" kể cả khi transaction chính rollback (outcome ATTEMPT/FAILURE).
 *   REQUIRES_NEW chiếm thêm một connection pool trong lúc giữ connection ngoài nên chỉ dùng cho thao tác nhạy cảm.
 * Không bao giờ lưu password / token / secret / signed URL: các khóa nhạy cảm bị che.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Set<String> SENSITIVE = Set.of("password", "oldpassword", "newpassword", "token", "refreshtoken",
            "accesstoken", "secret", "signature", "url", "code", "verificationcode", "apikey");

    private final AuditLogRepository repo;
    private final Clock clock;

    @Transactional
    public void record(String action, String entityType, Object entityId, String from, String to, String reason, Map<String, Object> details) {
        save(action, entityType, entityId, from, to, reason, details, "SUCCESS");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(String action, String entityType, Object entityId, String reason, Map<String, Object> details, String outcome) {
        save(action, entityType, entityId, null, null, reason, details, outcome);
    }

    /** Ghi audit cho hệ thống (job/webhook) không có người dùng hiện tại. */
    private void save(String action, String entityType, Object entityId, String from, String to, String reason,
                      Map<String, Object> details, String outcome) {
        AuditLog a = new AuditLog();
        CurrentUser.optional().ifPresent(u -> {
            a.setActorId(u.getId());
            a.setActorRoles(String.join(",", u.getRoles()));
        });
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId == null ? null : String.valueOf(entityId));
        a.setRequestId(MDC.get("traceId"));
        a.setFromState(from);
        a.setToState(to);
        a.setReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        a.setDetails(details == null ? null : Json.write(mask(details)));
        a.setOutcome(outcome);
        a.setAt(clock.instant());
        repo.save(a);
    }

    private Map<String, Object> mask(Map<String, Object> in) {
        return in.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> SENSITIVE.contains(e.getKey().toLowerCase()) ? (Object) "***" : (e.getValue() == null ? "" : e.getValue()),
                (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
