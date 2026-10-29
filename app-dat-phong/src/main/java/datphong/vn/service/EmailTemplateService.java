package datphong.vn.service;

import datphong.vn.exception.entity.EmailTemplate;
import datphong.vn.exception.entity.EmailTemplateVersion;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.EmailTemplateRepository;
import datphong.vn.repository.EmailTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/**
 * UC-31. Version đã publish là BẤT BIẾN; ACTIVE chỉ trỏ tới version mới nhất được publish.
 * Mail đã xếp hàng giữ template_version cũ (xem MailService); thu hồi/publish mới không làm mất lịch sử đã gửi.
 */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private static final Pattern UNSAFE_HTML = Pattern.compile("(?is)<script|javascript:|\\son[a-z]+\\s*=|<iframe|<object|<embed");

    private final EmailTemplateRepository templates;
    private final EmailTemplateVersionRepository versions;
    private final TemplateRenderer renderer;
    private final AuditService audit;
    private final Clock clock;

    public record Draft(String subjectTemplate, String htmlBody, String textBody, List<String> allowedVariables) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : templates.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("code", t.getTemplateCode());
            m.put("locale", t.getLocale());
            m.put("activeVersion", t.getActiveVersion());
            m.put("versions", versions.findByTemplateIdOrderByVersionDesc(t.getId()).stream()
                    .map(v -> Map.of("version", v.getVersion(), "status", v.getStatus())).toList());
            out.add(m);
        }
        return out;
    }

    /** Tạo template mới (nếu chưa có theo code+locale) hoặc DRAFT mới; sửa trực tiếp nếu đã có DRAFT. */
    @Transactional
    public Map<String, Object> saveDraft(String code, String locale, Draft d) {
        if (!TemplateCatalog.DEFAULTS.containsKey(code)) throw BusinessException.validation("Mã sự kiện email không hợp lệ: " + code);
        EmailTemplate t = templates.findByTemplateCodeAndLocale(code, locale).orElseGet(() -> {
            EmailTemplate n = new EmailTemplate();
            n.setTemplateCode(code);
            n.setLocale(locale);
            n.setCreatedAt(clock.instant());
            return templates.save(n);
        });
        Optional<EmailTemplateVersion> draft = versions.findByTemplateIdOrderByVersionDesc(t.getId()).stream().filter(v -> v.getStatus().equals("DRAFT")).findFirst();
        EmailTemplateVersion v = draft.orElseGet(() -> {
            EmailTemplateVersion n = new EmailTemplateVersion();
            n.setTemplateId(t.getId());
            n.setVersion(versions.maxVersion(t.getId()) + 1);
            n.setCreatedAt(clock.instant());
            n.setStatus("DRAFT");
            return n;
        });
        v.setSubjectTemplate(d.subjectTemplate());
        v.setHtmlBody(d.htmlBody());
        v.setTextBody(d.textBody());
        v.setAllowedVariables(String.join(",", d.allowedVariables()));
        versions.save(v);
        audit.record("EMAIL_TEMPLATE_DRAFT", "EMAIL_TEMPLATE", t.getId(), null, "DRAFT v" + v.getVersion(), null, null);
        return Map.of("templateId", t.getId(), "version", v.getVersion(), "status", "DRAFT");
    }

    /** Preview bằng dữ liệu giả lập (đã mask) và kiểm tra biến thiếu. Không chạy script/truy vấn DB. */
    @Transactional(readOnly = true)
    public TemplateRenderer.Rendered preview(Long templateId, int version) {
        EmailTemplateVersion v = versions.findByTemplateIdAndVersion(templateId, version).orElseThrow(() -> BusinessException.notFound("Phiên bản template"));
        EmailTemplate t = templates.findById(templateId).orElseThrow();
        Map<String, Object> sample = new HashMap<>();
        for (String name : v.getAllowedVariables().split(",")) sample.put(name, "<" + name + ">");
        sample.put("customerName", "Nguyễn V*** A");
        sample.put("maskedEmail", "ng***@example.com");
        try {
            return renderer.render(v.getSubjectTemplate(), v.getHtmlBody(), v.getTextBody(), new HashSet<>(Arrays.asList(v.getAllowedVariables().split(","))), sample);
        } catch (TemplateRenderer.MissingVariableException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> publish(Long templateId, int version) {
        EmailTemplate t = templates.findById(templateId).orElseThrow(() -> BusinessException.notFound("Template"));
        EmailTemplateVersion v = versions.findByTemplateIdAndVersion(templateId, version).orElseThrow(() -> BusinessException.notFound("Phiên bản template"));
        if (!v.getStatus().equals("DRAFT")) throw BusinessException.conflict("Chỉ DRAFT mới publish được; version đã publish là bất biến");
        TemplateCatalog.Def def = TemplateCatalog.DEFAULTS.get(t.getTemplateCode());
        Set<String> allowed = new HashSet<>(Arrays.asList(v.getAllowedVariables().split(",")));
        List<String> problems = new ArrayList<>();
        if (v.getSubjectTemplate() == null || v.getSubjectTemplate().isBlank()) problems.add("subject rỗng");
        if (v.getTextBody() == null || v.getTextBody().isBlank()) problems.add("thiếu bản plain text (fallback)");
        if (!def.vars().containsAll(allowed)) problems.add("allowedVariables chứa biến ngoài danh mục của sự kiện " + t.getTemplateCode());
        Set<String> used = renderer.variablesIn(v.getSubjectTemplate(), v.getHtmlBody(), v.getTextBody());
        for (String u : used) if (!allowed.contains(u)) problems.add("dùng biến không được phép: " + u);
        if (UNSAFE_HTML.matcher(v.getHtmlBody()).find()) problems.add("HTML chứa script/handler không an toàn");
        if (v.getHtmlBody().contains("X-Amz-") || v.getTextBody().contains("X-Amz-")) problems.add("không nhúng URL ký MinIO vào template");
        // biến BẮT BUỘC theo sự kiện phải được dùng ít nhất trong text body
        for (String req : def.vars()) if (req.equals("verificationLink") || req.equals("bookingCode") || req.equals("verificationCode")) {
            if (def.vars().contains(req) && !used.contains(req)) problems.add("thiếu biến bắt buộc: " + req);
        }
        // Render thử với dữ liệu mẫu (UC-31 bước 3): phát hiện biến thiếu/lỗi cú pháp trước khi thay bản đang hoạt động
        if (problems.isEmpty()) {
            Map<String, Object> sample = new HashMap<>();
            for (String name : allowed) sample.put(name, "<" + name + ">");
            try {
                renderer.render(v.getSubjectTemplate(), v.getHtmlBody(), v.getTextBody(), allowed, sample);
            } catch (TemplateRenderer.MissingVariableException | IllegalArgumentException e) {
                problems.add("render thử thất bại: " + e.getMessage());
            }
            String unclosed = (v.getSubjectTemplate() + v.getHtmlBody() + v.getTextBody()).replaceAll("\\{\\{\\s*[A-Za-z][A-Za-z0-9_]*\\s*}}", "");
            if (unclosed.contains("{{") || unclosed.contains("}}")) problems.add("cú pháp biến không hợp lệ (thiếu {{ hoặc }})");
        }
        if (!problems.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Template chưa đủ điều kiện publish", problems);

        versions.findByTemplateIdOrderByVersionDesc(templateId).stream().filter(x -> x.getStatus().equals("ACTIVE")).forEach(x -> x.setStatus("ARCHIVED"));
        v.setStatus("ACTIVE");
        v.setPublishedAt(clock.instant());
        t.setActiveVersion(version);
        audit.record("EMAIL_TEMPLATE_PUBLISH", "EMAIL_TEMPLATE", templateId, null, "ACTIVE v" + version, null, null);
        return Map.of("templateId", templateId, "activeVersion", version);
    }
}
