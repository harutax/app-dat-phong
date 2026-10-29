package datphong.vn.service;

import datphong.vn.config.AppProperties;
import datphong.vn.constant.MailStatus;
import datphong.vn.exception.entity.EmailTemplateVersion;
import datphong.vn.exception.entity.MailDeliveryAttempt;
import datphong.vn.exception.entity.MailEvent;
import datphong.vn.repository.EmailTemplateVersionRepository;
import datphong.vn.repository.MailDeliveryAttemptRepository;
import datphong.vn.repository.MailEventRepository;
import datphong.vn.util.Json;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UC-32/34: worker gửi email. Ba pha tách biệt, KHÔNG giữ transaction trong lúc gọi SMTP:
 *   (1) claim (SKIP LOCKED + lease) + ghi delivery_attempt  ->  commit
 *   (2) render + gửi SMTP
 *   (3) ghi kết quả.
 * Ngữ nghĩa at-least-once: crash sau khi gửi trước khi ghi kết quả => hết lease sẽ gửi lại (provider có thể nhận trùng; dedupe ở mức email logical).
 */
@Slf4j
@Service
public class MailWorker {

    private static final Duration LEASE = Duration.ofMinutes(2);

    private final MailEventRepository mails;
    private final MailDeliveryAttemptRepository attempts;
    private final EmailTemplateVersionRepository versions;
    private final TemplateRenderer renderer;
    private final JavaMailSender sender;
    private final ConfigService config;
    private final AppProperties props;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MailWorker(MailEventRepository mails, MailDeliveryAttemptRepository attempts, EmailTemplateVersionRepository versions,
                      TemplateRenderer renderer, JavaMailSender sender, ConfigService config, AppProperties props,
                      MeterRegistry metrics, PlatformTransactionManager txm, Clock clock) {
        this.mails = mails;
        this.attempts = attempts;
        this.versions = versions;
        this.renderer = renderer;
        this.sender = sender;
        this.config = config;
        this.props = props;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    public int runOnce(int max) {
        if (!props.getMail().isEnabled()) return 0;
        int n = 0;
        for (int i = 0; i < max; i++) {
            Long id = tx.execute(s -> claim());
            if (id == null) break;
            try {
                deliver(id);
            } catch (Exception e) {
                metrics.counter("scheduler_failures_total", "job", "mail").increment();
                log.warn("deliver mail {} crashed: {}", id, e.getMessage());
            }
            n++;
        }
        return n;
    }

    private Long claim() {
        Instant now = clock.instant();
        Optional<MailEvent> o = mails.claimNext(now);
        if (o.isEmpty()) return null;
        MailEvent m = o.get();
        m.setStatus(MailStatus.SENDING);
        m.setLeaseUntil(now.plus(LEASE));
        m.setAttemptCount(m.getAttemptCount() + 1);
        m.setUpdatedAt(now);
        MailDeliveryAttempt a = new MailDeliveryAttempt();
        a.setMailEventId(m.getId());
        a.setAttemptNo(m.getAttemptCount());
        a.setStartedAt(now);
        a.setOutcome("STARTED");
        attempts.save(a);
        metrics.timer("mail_queue_delay").record(Duration.between(m.getNextAttemptAt(), now));
        return m.getId();
    }

    private void deliver(Long id) {
        MailEvent m = mails.findById(id).orElseThrow();
        EmailTemplateVersion v = m.getTemplateVersionId() == null ? null : versions.findById(m.getTemplateVersionId()).orElse(null);
        if (v == null) { finish(id, MailStatus.FAILED_VALIDATION, "NO_TEMPLATE", null); return; }

        TemplateRenderer.Rendered r;
        try {
            Map<String, Object> vars = Json.readMap(m.getPayloadSnapshot());
            r = renderer.render(v.getSubjectTemplate(), v.getHtmlBody(), v.getTextBody(),
                    new HashSet<>(Arrays.asList(v.getAllowedVariables().split(","))), vars);
        } catch (TemplateRenderer.MissingVariableException e) {
            finish(id, MailStatus.FAILED_VALIDATION, "MISSING_VARIABLE", null);   // không gửi chuỗi {{...}} cho khách
            return;
        }

        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(props.getMail().getFrom());
            h.setTo(m.getRecipient());
            h.setSubject(r.subject());
            h.setText(r.text(), r.html());
            sender.send(msg);
            finish(id, MailStatus.SENT, null, msg.getMessageID());
            metrics.counter("mail_sent_total").increment();
        } catch (MailParseException e) {
            finish(id, MailStatus.FAILED, "INVALID_ADDRESS", null);               // lỗi vĩnh viễn: không retry
        } catch (MailAuthenticationException e) {
            retryOrDlq(id, "SMTP_AUTH");
        } catch (MailSendException e) {
            Throwable root = rootCause(e);
            if (root instanceof SocketTimeoutException) {
                finish(id, MailStatus.UNKNOWN, "TIMEOUT_AFTER_SEND", null);       // nhà cung cấp có thể đã nhận: không kết luận chưa gửi
            } else if (String.valueOf(root.getMessage()).matches("(?s).*\\b5\\d\\d\\b.*") && !String.valueOf(root.getMessage()).contains("452")) {
                finish(id, MailStatus.FAILED, "SMTP_5XX", null);
            } else {
                retryOrDlq(id, "SMTP_TRANSIENT");
            }
        } catch (Exception e) {
            retryOrDlq(id, e.getClass().getSimpleName());
        }
    }

    private void retryOrDlq(Long id, String code) {
        tx.executeWithoutResult(s -> {
            MailEvent m = mails.findById(id).orElseThrow();
            int max = config.getInt("mail.retry.maxAttempts");
            Instant now = clock.instant();
            closeAttempt(m, "RETRY", code, null, now);
            m.setErrorCode(code);
            m.setLeaseUntil(null);
            m.setUpdatedAt(now);
            if (m.getAttemptCount() >= max) {
                m.setStatus(MailStatus.DLQ);   // quá số lần retry -> DLQ cho SUPPORT xử lý
                metrics.counter("mail_dlq_total").increment();
            } else {
                long base = config.getInt("mail.retry.backoffSeconds");
                long delay = base * (1L << Math.min(m.getAttemptCount() - 1, 10));          // exponential
                delay += ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 4));    // jitter
                m.setStatus(MailStatus.QUEUED);
                m.setNextAttemptAt(now.plusSeconds(delay));
            }
        });
    }

    private void finish(Long id, MailStatus status, String code, String providerMessageId) {
        tx.executeWithoutResult(s -> {
            MailEvent m = mails.findById(id).orElseThrow();
            Instant now = clock.instant();
            closeAttempt(m, status.name(), code, providerMessageId, now);
            m.setStatus(status);
            m.setErrorCode(code);
            m.setProviderMessageId(providerMessageId);
            m.setLeaseUntil(null);
            m.setUpdatedAt(now);
            if (status == MailStatus.SENT) m.setSentAt(now);
        });
    }

    private void closeAttempt(MailEvent m, String outcome, String code, String providerId, Instant now) {
        attempts.findFirstByMailEventIdAndAttemptNoOrderByIdDesc(m.getId(), m.getAttemptCount()).ifPresent(a -> {
            a.setFinishedAt(now);
            a.setOutcome(outcome);
            a.setErrorCode(code);
            a.setProviderMessageId(providerId);
        });
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }
}
