package datphong.vn.service;

import datphong.vn.constant.MailEventType;
import datphong.vn.constant.OutboxStatus;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import datphong.vn.util.Json;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UC-17/32: đọc outbox theo batch (claim SKIP LOCKED), ánh xạ sự kiện -> notification + mail_event.
 * Recipient LUÔN được suy ra từ quan hệ thật trong DB (booking.customer, property.owner, participants),
 * không tin userId do payload/sự kiện ngoài đưa. Giao hàng at-least-once; consumer idempotent
 * (unique event_id+recipient cho notification, dedupe_key cho mail).
 */
@Slf4j
@Service
public class OutboxProcessor {

    private static final int MAX_RETRY = 10;

    private final OutboxEventRepository outbox;
    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final RefundRepository refunds;
    private final ConversationParticipantRepository participants;
    private final NotificationRepository notifications;
    private final MailService mail;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxProcessor(OutboxEventRepository outbox, BookingRepository bookings, PropertyRepository properties, UserRepository users,
                           RefundRepository refunds, ConversationParticipantRepository participants, NotificationRepository notifications,
                           MailService mail, MeterRegistry metrics, PlatformTransactionManager txm, Clock clock) {
        this.outbox = outbox;
        this.bookings = bookings;
        this.properties = properties;
        this.users = users;
        this.refunds = refunds;
        this.participants = participants;
        this.notifications = notifications;
        this.mail = mail;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
    }

    public int runOnce(int max) {
        int n = 0;
        for (int i = 0; i < max; i++) {
            final Long[] current = new Long[1];
            try {
                Boolean got = tx.execute(s -> {
                    var o = outbox.claimNext(clock.instant());
                    if (o.isEmpty()) return false;
                    OutboxEvent e = o.get();
                    current[0] = e.getId();
                    handle(e);
                    e.setStatus(OutboxStatus.DONE);
                    e.setProcessedAt(clock.instant());
                    return true;
                });
                if (!Boolean.TRUE.equals(got)) break;
                n++;
            } catch (Exception ex) {
                // transaction đã rollback (kể cả notification đã tạo dở): ghi nhận lỗi + backoff ĐÚNG event vừa lỗi trong transaction riêng
                metrics.counter("scheduler_failures_total", "job", "outbox").increment();
                log.warn("outbox event {} failed: {}", current[0], ex.getMessage());
                if (current[0] != null) markFailure(current[0], ex.getClass().getSimpleName() + ": " + ex.getMessage());
                break;
            }
        }
        return n;
    }

    /** Đánh dấu lỗi cho đúng event theo id (không "claim lại event đầu hàng đợi" vì có thể là event khác do instance khác đã lấy). */
    private void markFailure(Long eventId, String err) {
        tx.executeWithoutResult(s -> outbox.findById(eventId).ifPresent(e -> {
            if (e.getStatus() == OutboxStatus.DONE) return;
            e.setRetryCount(e.getRetryCount() + 1);
            e.setLastError(err == null ? null : err.substring(0, Math.min(500, err.length())));
            if (e.getRetryCount() >= MAX_RETRY) {
                e.setStatus(OutboxStatus.FAILED);
            } else {
                e.setStatus(OutboxStatus.PENDING);
                e.setNextAttemptAt(clock.instant().plusSeconds(Math.min(600, 5L * (1L << Math.min(e.getRetryCount(), 7)))));
            }
        }));
    }

    // ------------------------------------------------------------------ mapping sự kiện
    private void handle(OutboxEvent e) {
        Map<String, Object> p = Json.readMap(e.getPayload());
        switch (e.getEventType()) {
            case "ACCOUNT_CREATED" -> accountMail(MailEventType.ACCOUNT_CREATED, e, p);
            case "EMAIL_VERIFICATION" -> accountMail(MailEventType.EMAIL_VERIFICATION, e, p);
            case "BOOKING_CONFIRMED" -> {
                Booking b = booking(p);
                Property pr = property(b);
                User c = customer(b);
                notify(e, b.getCustomerId(), "Đặt phòng đã được xác nhận", "Booking " + b.getBookingCode() + " tại " + pr.getName(), "BOOKING", b.getId());
                notify(e, pr.getOwnerId(), "Có booking mới", "Booking " + b.getBookingCode() + " (" + b.getCheckIn() + " → " + b.getCheckOut() + ")", "BOOKING", b.getId());
                Map<String, Object> v = vars(c);
                v.put("bookingCode", b.getBookingCode());
                v.put("propertyName", pr.getName());
                v.put("checkInDate", b.getCheckIn().toString());
                v.put("checkOutDate", b.getCheckOut().toString());
                v.put("totalAmount", b.getTotalAmount().toPlainString());
                v.put("currency", b.getCurrency());
                mail.enqueue(MailEventType.BOOKING_CONFIRMED, "bk" + b.getId(), b.getId(), c.getEmail(), v, null);
            }
            case "PAYMENT_SUCCEEDED" -> {
                Booking b = booking(p);
                User c = customer(b);
                Map<String, Object> v = vars(c);
                v.put("bookingCode", b.getBookingCode());
                v.put("amount", String.valueOf(p.get("amount")));
                v.put("currency", String.valueOf(p.get("currency")));
                mail.enqueue(MailEventType.PAYMENT_SUCCEEDED, "pay" + p.get("paymentId"), b.getId(), c.getEmail(), v, null);
            }
            case "PAYMENT_FAILED" -> {
                Booking b = booking(p);
                User c = customer(b);
                notify(e, b.getCustomerId(), "Thanh toán chưa thành công", "Booking " + b.getBookingCode() + ": bạn có thể thử lại trong thời hạn giữ chỗ", "BOOKING", b.getId());
                Map<String, Object> v = vars(c);
                v.put("bookingCode", b.getBookingCode());
                v.put("amount", String.valueOf(p.get("amount")));
                v.put("currency", String.valueOf(p.get("currency")));
                mail.enqueue(MailEventType.PAYMENT_FAILED, "pay" + p.get("paymentId"), b.getId(), c.getEmail(), v, null);
            }
            case "BOOKING_CREATED" -> {
                // UC-09/17: khách biết đang giữ chỗ tới khi nào; HOST biết có booking chờ thanh toán ở cơ sở mình
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Đang giữ chỗ cho bạn", "Booking " + b.getBookingCode() + " - vui lòng thanh toán trước khi hết hạn giữ chỗ", "BOOKING", b.getId());
                notify(e, property(b).getOwnerId(), "Có booking mới chờ thanh toán", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "REFUND_REQUESTED" -> {
                // UC-12/13: hủy booking không đồng nghĩa hoàn tiền tức thời - báo khách yêu cầu hoàn đã được ghi nhận
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Đã ghi nhận yêu cầu hoàn tiền", "Booking " + b.getBookingCode() + " - tiền hoàn sẽ được xử lý qua ngân hàng", "BOOKING", b.getId());
            }
            case "BOOKING_EXPIRED" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Giữ chỗ đã hết hạn", "Booking " + b.getBookingCode() + " đã hết hạn thanh toán", "BOOKING", b.getId());
            }
            case "BOOKING_CANCELLED" -> {
                Booking b = booking(p);
                Property pr = property(b);
                notify(e, b.getCustomerId(), "Booking đã được hủy", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
                notify(e, pr.getOwnerId(), "Một booking đã bị hủy", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "REFUND_SUCCEEDED", "REFUND_FAILED" -> {
                Refund r = refunds.findById(Long.valueOf(String.valueOf(p.get("refundId")))).orElseThrow();
                Booking b = bookings.findById(r.getBookingId()).orElseThrow();
                User c = customer(b);
                boolean ok = e.getEventType().equals("REFUND_SUCCEEDED");
                notify(e, b.getCustomerId(), ok ? "Đã hoàn tiền" : "Hoàn tiền đang được xử lý thủ công", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
                Map<String, Object> v = vars(c);
                v.put("bookingCode", b.getBookingCode());
                v.put("refundAmount", r.getAmount().toPlainString());
                v.put("currency", b.getCurrency());
                v.put("refundCode", "RF" + r.getId());
                // email "thành công" CHỈ gửi khi refund ở trạng thái SUCCEEDED thật
                if (ok && r.getStatus() == datphong.vn.constant.RefundStatus.SUCCEEDED) mail.enqueue(MailEventType.REFUND_SUCCEEDED, "rf" + r.getId(), b.getId(), c.getEmail(), v, null);
                if (!ok) mail.enqueue(MailEventType.REFUND_FAILED, "rf" + r.getId(), b.getId(), c.getEmail(), v, null);
            }
            case "BOOKING_CHECKED_IN" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Bạn đã nhận phòng", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "BOOKING_COMPLETED" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Hoàn tất lưu trú", "Cảm ơn bạn! Hãy chia sẻ đánh giá cho booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "HOST_BOOKING_UPDATED" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Chủ cơ sở cập nhật booking", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "MESSAGE_SENT" -> {
                Long cid = Long.valueOf(String.valueOf(p.get("conversationId")));
                Long sender = Long.valueOf(String.valueOf(p.get("senderId")));
                for (ConversationParticipant cp : participants.findByConversationId(cid)) {
                    if (!cp.getUserId().equals(sender)) notify(e, cp.getUserId(), "Tin nhắn mới", "Bạn có tin nhắn mới trong hội thoại #" + cid, "CONVERSATION", cid);
                }
            }
            case "CHECKIN_REQUEST_CREATED" -> {
                Booking b = booking(p);
                notify(e, property(b).getOwnerId(), "Yêu cầu giờ check-in", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "CHECKIN_REQUEST_RESPONDED" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Chủ cơ sở đã phản hồi giờ check-in", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "CHECKIN_REQUEST_ACCEPTED" -> {
                Booking b = booking(p);
                notify(e, property(b).getOwnerId(), "Khách đã đồng ý giờ check-in đề xuất", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "BOOKING_NO_SHOW" -> {
                Booking b = booking(p);
                notify(e, b.getCustomerId(), "Booking được ghi nhận vắng mặt", "Booking " + b.getBookingCode() + " đã bị đánh dấu không đến nhận phòng", "BOOKING", b.getId());
            }
            case "COMPLAINT_CREATED" -> {
                Booking b = booking(p);
                notify(e, property(b).getOwnerId(), "Phản ánh mới từ khách", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
            }
            case "COMPLAINT_REPLY" -> {
                Booking b = booking(p);
                User c = customer(b);
                notify(e, b.getCustomerId(), "Chủ cơ sở đã phản hồi phản ánh", "Booking " + b.getBookingCode(), "BOOKING", b.getId());
                Map<String, Object> v = vars(c);
                v.put("propertyName", property(b).getName());
                v.put("bookingCode", b.getBookingCode());
                mail.enqueue(MailEventType.COMPLAINT_REPLY, "cr" + e.getEventId(), b.getId(), c.getEmail(), v, null);
            }
            default -> log.debug("outbox event {} không cần xử lý", e.getEventType());
        }
    }

    private void accountMail(MailEventType type, OutboxEvent e, Map<String, Object> p) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (String k : List.of("customerName", "maskedEmail", "verificationLink", "verificationCode")) if (p.get(k) != null) v.put(k, p.get(k));
        mail.enqueue(type, type == MailEventType.ACCOUNT_CREATED ? "u" + p.get("userId") : "ev" + e.getEventId(), null, String.valueOf(p.get("email")), v, null);
    }

    private Booking booking(Map<String, Object> p) {
        return bookings.findById(Long.valueOf(String.valueOf(p.get("bookingId")))).orElseThrow();
    }

    private Property property(Booking b) {
        return properties.findById(b.getPropertyId()).orElseThrow();
    }

    private User customer(Booking b) {
        return users.findById(b.getCustomerId()).orElseThrow();
    }

    private Map<String, Object> vars(User c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("customerName", c.getFullName());
        return v;
    }

    private void notify(OutboxEvent e, Long recipientId, String title, String body, String objType, Long objId) {
        notifications.insertIgnore(e.getEventId(), recipientId, e.getEventType(), title, body, objType, String.valueOf(objId), clock.instant());
    }

}
