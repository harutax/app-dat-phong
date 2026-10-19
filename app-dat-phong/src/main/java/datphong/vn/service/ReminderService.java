package datphong.vn.service;

import datphong.vn.constant.CheckinRequestStatus;
import datphong.vn.constant.MailEventType;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UC-33. dueAt = giờ nhận phòng - duration, tính trên ZonedDateTime của múi giờ property (đúng qua DST, không cộng 24h cố định),
 * lưu UTC. Dedupe bằng unique (booking, type, window, policy_version) ở DB; claim bằng SKIP LOCKED.
 * Quy tắc cấu hình EFFECTIVE_FOR_UNSENT: đổi cấu hình áp dụng cho reminder CHƯA gửi; reminder đã QUEUED/gửi không bị gửi lại.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    static final String TYPE = "CHECKIN_REMINDER";
    static final String WINDOW = "BEFORE_CHECKIN";

    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final ScheduledReminderRepository reminders;
    private final MailEventRepository mailEvents;
    private final CheckinRequestRepository checkinRequests;
    private final MailService mail;
    private final PricingService pricing;
    private final ConfigService config;
    private final PlatformTransactionManager txm;
    private final MeterRegistry metrics;
    private final Clock clock;

    /** Hủy reminder chưa gửi + mail reminder đang xếp hàng của booking (hủy/hết hạn/đã check-in). */
    @Transactional
    public void cancelForBooking(Long bookingId) {
        Instant now = clock.instant();
        reminders.cancelPending(bookingId, now);
        mailEvents.cancelQueuedReminders(bookingId, now);
    }

    /** Bước 1: tạo/đồng bộ lịch nhắc cho booking CONFIRMED sắp tới. */
    public int materialize() {
        if (!config.getBool("checkin.reminder.enabled")) return 0;
        int before = config.getInt("checkin.reminder.beforeMinutes");
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.plusDays(before / 1440 + 3);
        TransactionTemplate tx = new TransactionTemplate(txm);
        Integer n = tx.execute(s -> {
            int created = 0;
            for (Booking b : bookings.findConfirmedCheckInBetween(today.minusDays(1), to, PageRequest.of(0, 500))) {
                created += syncOne(b, before) ? 1 : 0;
            }
            return created;
        });
        return n == null ? 0 : n;
    }

    private boolean syncOne(Booking b, int beforeMinutes) {
        Property p = properties.findById(b.getPropertyId()).orElse(null);
        if (p == null) return false;
        Instant scheduled = agreedOrDefault(b, p);
        Instant due = scheduled.minus(Duration.ofMinutes(beforeMinutes)); // Duration trên Instant: chính xác, DST đã nằm trong scheduled
        if (!scheduled.isAfter(clock.instant())) return false;
        List<ScheduledReminder> existing = reminders.findByBookingIdAndReminderTypeAndWindowCode(b.getId(), TYPE, WINDOW);
        if (existing.stream().anyMatch(r -> r.getStatus().equals("QUEUED"))) return false; // đã gửi: không gửi lại cùng mốc
        int policyVersion = beforeMinutes;
        ScheduledReminder current = null;
        for (ScheduledReminder r : existing) {
            if (!r.getStatus().equals("PENDING")) continue;
            if (r.getPolicyVersion() == policyVersion) current = r;
            else { r.setStatus("CANCELLED"); r.setUpdatedAt(clock.instant()); reminders.save(r); } // policy cũ chưa gửi bị vô hiệu
        }
        if (current != null) {
            if (!current.getDueAt().equals(due)) { current.setDueAt(due); current.setUpdatedAt(clock.instant()); reminders.save(current); } // giờ hẹn đổi
            return false;
        }
        if (existing.stream().anyMatch(r -> r.getPolicyVersion() == policyVersion)) return false; // unique key đã tồn tại (đã CANCELLED/SKIPPED)
        ScheduledReminder r = new ScheduledReminder();
        r.setBookingId(b.getId());
        r.setReminderType(TYPE);
        r.setWindowCode(WINDOW);
        r.setPolicyVersion(policyVersion);
        r.setDueAt(due);
        r.setStatus("PENDING");
        r.setCreatedAt(clock.instant());
        r.setUpdatedAt(clock.instant());
        reminders.save(r);
        return true;
    }

    private Instant agreedOrDefault(Booking b, Property p) {
        return checkinRequests.findFirstByBookingIdAndStatusInOrderByIdDesc(b.getId(),
                        List.of(CheckinRequestStatus.APPROVED, CheckinRequestStatus.ACCEPTED))
                .map(CheckinRequest::getAgreedCheckinAt).filter(java.util.Objects::nonNull)
                .orElseGet(() -> pricing.scheduledCheckIn(p, b.getCheckIn()));
    }

    /** Bước 2: đẩy các reminder đến hạn vào hàng đợi mail (mỗi lượt một transaction, claim SKIP LOCKED). */
    public int dispatch() {
        TransactionTemplate tx = new TransactionTemplate(txm);
        Integer n = tx.execute(s -> {
            int sent = 0;
            Instant now = clock.instant();
            int cutoff = config.getInt("checkin.reminder.cutoffMinutes");
            for (ScheduledReminder r : reminders.claimDue(now, 50)) {
                Booking b = bookings.findById(r.getBookingId()).orElse(null);
                if (b == null || b.getStatus() != datphong.vn.constant.BookingStatus.CONFIRMED || b.getActualCheckIn() != null) {
                    r.setStatus("CANCELLED"); r.setUpdatedAt(now); continue;
                }
                if (now.isAfter(r.getDueAt().plus(Duration.ofMinutes(cutoff)))) {   // trễ quá cutoff: nhắc đã vô nghĩa
                    r.setStatus("SKIPPED"); r.setUpdatedAt(now);
                    metrics.counter("checkin_reminder_late_total").increment();
                    continue;
                }
                User u = users.findById(b.getCustomerId()).orElse(null);
                Property p = properties.findById(b.getPropertyId()).orElse(null);
                if (u == null || p == null || u.getVerifiedAt() == null) { r.setStatus("SKIPPED"); r.setUpdatedAt(now); continue; }
                Instant at = agreedOrDefault(b, p);
                ZonedDateTime local = at.atZone(ZoneId.of(p.getTimezone()));
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("customerName", u.getFullName());
                vars.put("bookingCode", b.getBookingCode());
                vars.put("propertyName", p.getName());
                vars.put("checkInDate", local.toLocalDate().toString());
                vars.put("checkInTime", local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
                vars.put("propertyAddress", p.getAddress() == null ? "" : p.getAddress());
                vars.put("contactLink", "/bookings/" + b.getId());
                MailEvent me = mail.enqueue(MailEventType.CHECKIN_REMINDER, "bk" + b.getId() + ":" + r.getWindowCode() + ":" + r.getPolicyVersion(),
                        b.getId(), u.getEmail(), vars, r.getDueAt());
                r.setStatus("QUEUED");
                r.setMailEventId(me == null ? null : me.getId());
                r.setUpdatedAt(now);
                sent++;
            }
            return sent;
        });
        return n == null ? 0 : n;
    }
}
