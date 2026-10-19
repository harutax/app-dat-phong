package datphong.vn.scheduler;

import datphong.vn.config.AppProperties;
import datphong.vn.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tất cả job nền. Mỗi job idempotent, xử lý theo batch, claim bằng SKIP LOCKED/lease trong DB (không dựa Redis lock),
 * nên chạy được trên nhiều instance. Lỗi một bản ghi không làm sập cả job; số lỗi đếm ở metric scheduler_failures_total.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Schedulers {

    private final AppProperties props;
    private final BookingExpiryService expiry;
    private final ReconciliationService reconciliation;
    private final OutboxProcessor outbox;
    private final MailWorker mailWorker;
    private final ReminderService reminders;
    private final MediaService media;

    private boolean off() {
        return !props.getScheduler().isEnabled();
    }

    /** UC-11: hết hạn giữ chỗ. */
    @Scheduled(fixedDelayString = "${app.scheduler.expiry-fixed-delay-ms:15000}", initialDelay = 10_000)
    public void expireHolds() {
        if (off()) return;
        try { expiry.runOnce(); } catch (Exception e) { log.error("hold expiry job failed", e); }
    }

    /** UC-17: outbox -> notification + mail_event. */
    @Scheduled(fixedDelay = 3_000, initialDelay = 5_000)
    public void outbox() {
        if (off()) return;
        try { outbox.runOnce(50); } catch (Exception e) { log.error("outbox job failed", e); }
    }

    /** UC-32/34: gửi email. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 7_000)
    public void mail() {
        if (off()) return;
        try { mailWorker.runOnce(20); } catch (Exception e) { log.error("mail job failed", e); }
    }

    /** UC-13/35/36: đối soát payment (UNKNOWN/PROCESSING/mất callback) và xử lý/đối soát refund. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 15_000)
    public void reconcile() {
        if (off()) return;
        try {
            reconciliation.reconcilePayments();
            reconciliation.processRefunds();
        } catch (Exception e) { log.error("reconcile job failed", e); }
    }

    /** UC-33: lập lịch + đẩy nhắc check-in. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void reminders() {
        if (off()) return;
        try {
            reminders.materialize();
            reminders.dispatch();
        } catch (Exception e) { log.error("reminder job failed", e); }
    }

    /** UC-06: dọn object ảnh mồ côi / hết hạn. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void mediaCleanup() {
        if (off()) return;
        try { media.cleanup(); } catch (Exception e) { log.error("media cleanup failed", e); }
    }
}
