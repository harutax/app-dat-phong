package datphong.vn.service;

import datphong.vn.constant.MailEventType;
import datphong.vn.constant.MailStatus;
import datphong.vn.exception.entity.EmailTemplate;
import datphong.vn.exception.entity.EmailTemplateVersion;
import datphong.vn.exception.entity.MailEvent;
import datphong.vn.repository.EmailTemplateRepository;
import datphong.vn.repository.EmailTemplateVersionRepository;
import datphong.vn.repository.MailEventRepository;
import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * UC-32: tạo email LOGICAL từ business event. Mail là side effect bất đồng bộ - không bao giờ gọi SMTP trong transaction nghiệp vụ.
 * dedupe_key = eventType + businessId + recipient -> callback/sự kiện lặp chỉ tạo một email logical.
 * Version template được GHIM vào mail_event + snapshot biến: retry sau khi admin publish bản mới vẫn gửi đúng nội dung cũ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final MailEventRepository mails;
    private final EmailTemplateRepository templates;
    private final EmailTemplateVersionRepository versions;
    private final ConfigService config;
    private final Clock clock;

    /** @return mail_event mới, hoặc bản đã có (dedupe). */
    @Transactional(propagation = Propagation.REQUIRED)
    public MailEvent enqueue(MailEventType type, String businessId, Long bookingId, String recipient, Map<String, Object> vars, Instant dueAt) {
        Optional<EmailTemplateVersion> v = activeVersion(type.name());
        // Dedupe theo sự kiện/khóa nghiệp vụ + người nhận (KHÔNG gồm template version): admin publish mẫu mới
        // không được làm một sự kiện đã xếp hàng sinh thêm email thứ hai.
        String dedupe = type + "|" + businessId + "|" + recipient.toLowerCase();
        if (mails.existsByDedupeKey(dedupe)) return null;
        MailEvent m = new MailEvent();
        m.setDedupeKey(dedupe);
        m.setEventType(type.name());
        m.setBusinessId(businessId);
        m.setBookingId(bookingId);
        m.setRecipient(recipient.toLowerCase());
        m.setPayloadSnapshot(Json.write(vars));
        m.setNextAttemptAt(clock.instant());
        m.setDueAt(dueAt);
        m.setCreatedAt(clock.instant());
        m.setUpdatedAt(clock.instant());
        if (v.isPresent()) {
            m.setTemplateVersionId(v.get().getId());
            m.setStatus(MailStatus.QUEUED);
        } else {
            m.setStatus(MailStatus.FAILED_VALIDATION);
            m.setErrorCode("NO_ACTIVE_TEMPLATE");
        }
        return mails.save(m);
    }

    /** Template ACTIVE theo locale mặc định; không có thì thử mọi locale. Không tự thay thông tin pháp lý khác locale. */
    public Optional<EmailTemplateVersion> activeVersion(String code) {
        String locale = config.getString("mail.defaultLocale");
        Optional<EmailTemplate> t = templates.findByTemplateCodeAndLocale(code, locale);
        return t.filter(x -> x.getActiveVersion() != null).flatMap(x -> versions.findByTemplateIdAndVersion(x.getId(), x.getActiveVersion()));
    }
}
