package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.CheckinRequestStatus;
import datphong.vn.constant.ConversationType;
import datphong.vn.constant.MessageType;
import datphong.vn.dto.request.MessagingRequests;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * UC-29. checkin_request là LỊCH HẸN tách khỏi booking.status: chấp nhận trong chat KHÔNG đổi booking thành CHECKED_IN.
 * Hai HOST phản hồi đồng thời -> @Version: chỉ một thay đổi hợp lệ, bên kia nhận VERSION_CONFLICT.
 * Booking hủy -> request tự đóng (xem CancellationService/StayService) và reminder bị vô hiệu.
 */
@Service
@RequiredArgsConstructor
public class CheckinRequestService {

    private final CheckinRequestRepository requests;
    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final ConversationService chat;
    private final OutboxService outbox;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public CheckinRequest create(Long customerId, Long bookingId, MessagingRequests.CheckinCreate r) {
        Booking b = bookings.lockById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));   // cùng thứ tự khóa với hủy
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.notFound("Booking");
        if (b.getStatus() != BookingStatus.CONFIRMED) throw BusinessException.conflict("Chỉ gửi yêu cầu cho booking CONFIRMED");
        Property p = properties.findById(b.getPropertyId()).orElseThrow();
        ZonedDateTime dayStart = b.getCheckIn().atTime(LocalTime.MIDNIGHT).atZone(ZoneId.of(p.getTimezone()));
        Instant from = dayStart.toInstant();
        Instant to = dayStart.plus(Duration.ofHours(30)).toInstant();      // cho phép đến muộn qua nửa đêm
        if (r.proposedAt().isBefore(from) || r.proposedAt().isAfter(to) || r.proposedAt().isBefore(clock.instant())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Giờ đề nghị nằm ngoài thời gian chấp nhận của booking",
                    Map.of("earliest", from.toString(), "latest", to.toString()));
        }
        if (requests.findFirstByBookingIdAndStatusInOrderByIdDesc(bookingId, List.of(CheckinRequestStatus.PENDING, CheckinRequestStatus.COUNTER_PROPOSED)).isPresent()) {
            throw BusinessException.conflict("Đã có yêu cầu check-in đang chờ xử lý");
        }
        Conversation c = conversations.findActive(customerId, b.getPropertyId(), ConversationType.BOOKING_SUPPORT, bookingId).orElseGet(() -> {
            Conversation n = new Conversation();
            n.setPropertyId(b.getPropertyId());
            n.setBookingId(bookingId);
            n.setCustomerId(customerId);
            n.setHostId(p.getOwnerId());
            n.setType(ConversationType.BOOKING_SUPPORT);
            conversations.saveAndFlush(n);
            participants.save(new ConversationParticipant(n.getId(), customerId, "CUSTOMER"));
            participants.save(new ConversationParticipant(n.getId(), p.getOwnerId(), "HOST"));
            return n;
        });
        Conversation locked = conversations.lockById(c.getId()).orElseThrow();
        CheckinRequest cr = new CheckinRequest();
        cr.setBookingId(bookingId);
        cr.setConversationId(c.getId());
        cr.setProposedAt(r.proposedAt());
        cr.setNote(r.note());
        cr.setStatus(CheckinRequestStatus.PENDING);
        requests.saveAndFlush(cr);
        chat.appendMessage(locked, customerId, MessageType.CHECKIN_REQUEST,
                "Đề nghị nhận phòng lúc " + r.proposedAt() + (r.note() == null ? "" : " - " + r.note()), "cin-" + cr.getId());
        outbox.publish("CHECKIN_REQUEST_CREATED:" + cr.getId(), "CHECKIN_REQUEST_CREATED", "CHECKIN_REQUEST", cr.getId(), Map.of("bookingId", bookingId));
        return cr;
    }

    @Transactional
    public CheckinRequest respond(Long ownerId, Long requestId, MessagingRequests.CheckinRespond r) {
        CheckinRequest cr = requests.findById(requestId).orElseThrow(() -> BusinessException.notFound("Yêu cầu check-in"));
        Booking b = bookings.lockById(cr.getBookingId()).orElseThrow();
        properties.findByIdAndOwnerId(b.getPropertyId(), ownerId).orElseThrow(() -> BusinessException.notFound("Yêu cầu check-in"));
        if (cr.getVersion() != r.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Yêu cầu đã được xử lý bởi người khác");
        if (cr.getStatus() != CheckinRequestStatus.PENDING) throw BusinessException.conflict("Yêu cầu không ở trạng thái chờ phản hồi");
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            cr.setStatus(CheckinRequestStatus.CLOSED);
            requests.saveAndFlush(cr);
            throw BusinessException.conflict("Booking không còn hiệu lực, yêu cầu đã đóng");
        }
        String text;
        switch (r.decision()) {
            case "APPROVED" -> { cr.setStatus(CheckinRequestStatus.APPROVED); cr.setAgreedCheckinAt(cr.getProposedAt()); text = "Chủ cơ sở đồng ý giờ nhận phòng " + cr.getProposedAt(); }
            case "REJECTED" -> {
                cr.setStatus(CheckinRequestStatus.REJECTED);
                text = "Chủ cơ sở từ chối: " + (r.note() == null ? "" : r.note());
            }
            case "COUNTER_PROPOSED" -> {
                if (r.counterAt() == null) throw BusinessException.validation("Cần counterAt");
                cr.setStatus(CheckinRequestStatus.COUNTER_PROPOSED);
                cr.setAgreedCheckinAt(r.counterAt());     // tạm thời, chỉ có hiệu lực khi khách ACCEPT
                text = "Chủ cơ sở đề nghị giờ nhận phòng " + r.counterAt();
            }
            default -> throw BusinessException.validation("decision phải là APPROVED | REJECTED | COUNTER_PROPOSED");
        }
        cr.setRespondedBy(ownerId);
        cr.setResponseNote(r.note());
        requests.saveAndFlush(cr);
        Conversation locked = conversations.lockById(cr.getConversationId()).orElseThrow();
        chat.appendMessage(locked, ownerId, MessageType.CHECKIN_REQUEST, text, "cin-resp-" + cr.getId() + "-" + cr.getVersion());
        outbox.publish("CHECKIN_REQUEST_RESPONDED:" + cr.getId() + ":" + cr.getVersion(), "CHECKIN_REQUEST_RESPONDED", "CHECKIN_REQUEST", cr.getId(), Map.of("bookingId", b.getId()));
        audit.record("CHECKIN_REQUEST_" + r.decision(), "CHECKIN_REQUEST", cr.getId(), "PENDING", cr.getStatus().name(), r.note(), null);
        return cr;
    }

    /** Khách đồng ý đề xuất khác: lưu agreed_checkin_at; đây chỉ là giờ hẹn, KHÔNG đổi booking thành CHECKED_IN. */
    @Transactional
    public CheckinRequest accept(Long customerId, Long requestId) {
        CheckinRequest cr = requests.findById(requestId).orElseThrow(() -> BusinessException.notFound("Yêu cầu check-in"));
        Booking b = bookings.lockById(cr.getBookingId()).orElseThrow();
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.notFound("Yêu cầu check-in");
        if (cr.getStatus() != CheckinRequestStatus.COUNTER_PROPOSED) throw BusinessException.conflict("Không có đề xuất nào để chấp nhận");
        if (b.getStatus() != BookingStatus.CONFIRMED) throw BusinessException.conflict("Booking không còn hiệu lực");
        cr.setStatus(CheckinRequestStatus.ACCEPTED);
        requests.saveAndFlush(cr);
        return cr;
    }

    @Transactional(readOnly = true)
    public List<CheckinRequest> forBooking(Long bookingId) {
        return requests.findByBookingIdOrderByIdDesc(bookingId);
    }
}
