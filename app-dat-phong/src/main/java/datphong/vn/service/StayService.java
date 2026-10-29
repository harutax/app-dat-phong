package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.RoomStatus;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.entity.Booking;
import datphong.vn.exception.entity.Property;
import datphong.vn.exception.entity.Room;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UC-14/15/29: HOST xử lý booking, check-in/out, no-show. Mọi thao tác: khóa booking -> recheck trạng thái -> chuyển -> history/audit/outbox.
 * HOST KHÔNG được sửa totalAmount / payment status / booking COMPLETED qua API cập nhật chung.
 * Hoàn tất lưu trú không cho phép thay đổi số tiền đã xác nhận.
 */
@Service
@RequiredArgsConstructor
public class StayService {

    private final BookingRepository bookings;
    private final PropertyRepository properties;
    private final RoomRepository rooms;
    private final BookingTransitions transitions;
    private final PricingService pricing;
    private final ReminderService reminders;
    private final CheckinRequestRepository checkinRequests;
    private final ConfigService config;
    private final OutboxService outbox;
    private final BusinessEventService analytics;
    private final AuditService audit;
    private final Clock clock;

    /** Khóa booking và ép owner scope: booking của cơ sở khác -> 404 (không lộ tồn tại). */
    private record Scoped(Booking booking, Property property) {}

    private Scoped lockScoped(Long ownerId, Long bookingId) {
        Booking b = bookings.lockById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));
        Property p = properties.findByIdAndOwnerId(b.getPropertyId(), ownerId).orElseThrow(() -> BusinessException.notFound("Booking"));
        return new Scoped(b, p);
    }

    @Transactional
    public void updateRequest(Long ownerId, Long bookingId, BookingRequests.HostUpdate req) {
        Scoped s = lockScoped(ownerId, bookingId);
        Booking b = s.booking();
        if (b.getVersion() != req.version()) throw new BusinessException(ErrorCode.VERSION_CONFLICT, "Booking đã thay đổi, hãy tải lại");
        if (b.getStatus() != BookingStatus.CONFIRMED && b.getStatus() != BookingStatus.CHECKED_IN) {
            throw BusinessException.conflict("Chỉ cập nhật yêu cầu cho booking đang hiệu lực");
        }
        // CHỈ các trường được phép: giờ đến / ghi chú / yêu cầu đặc biệt. Không có đường nào sửa tiền / paymentStatus / số đêm.
        List<String> changed = new ArrayList<>();
        if (req.specialNote() != null) { b.setSpecialNote(req.specialNote()); changed.add("specialNote"); }
        if (req.hostNote() != null) { b.setHostNote(req.hostNote()); changed.add("hostNote"); }
        if (req.expectedArrivalTime() != null) { b.setExpectedArrivalTime(req.expectedArrivalTime()); changed.add("expectedArrivalTime"); }
        if (changed.isEmpty()) throw BusinessException.validation("Không có trường nào được cập nhật");
        bookings.saveAndFlush(b);
        audit.record("HOST_BOOKING_UPDATE", "BOOKING", bookingId, null, null, null, Map.of("fields", changed));
        // Thông báo cho khách chỉ khi thay đổi khách nhìn thấy (ghi chú nội bộ của HOST không gửi đi)
        if (changed.contains("specialNote") || changed.contains("expectedArrivalTime")) {
            outbox.publish("HOST_BOOKING_UPDATED:" + bookingId + ":" + b.getVersion(), "HOST_BOOKING_UPDATED", "BOOKING", bookingId,
                    Map.of("bookingId", bookingId));
        }
    }

    @Transactional
    public void checkIn(Long ownerId, Long bookingId) {
        Scoped s = lockScoped(ownerId, bookingId);
        Booking b = s.booking();
        if (b.getStatus() == BookingStatus.CHECKED_IN) return;      // thao tác lặp / hai quầy lễ tân: trả kết quả hiện có
        if (b.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Booking " + b.getStatus() + " không thể check-in");
        }
        Instant now = clock.instant();
        Instant opens = java.time.ZonedDateTime.of(b.getCheckIn(), java.time.LocalTime.MIDNIGHT, java.time.ZoneId.of(s.property().getTimezone())).toInstant();
        Instant closes = pricing.scheduledCheckOut(s.property(), b.getCheckOut());
        if (now.isBefore(opens) || now.isAfter(closes)) {
            throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Ngoài cửa sổ check-in của booking (theo múi giờ cơ sở)");
        }
        Room room = rooms.findById(b.getRoomId()).orElseThrow();
        if (room.getStatus() == RoomStatus.MAINTENANCE) throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Phòng đang bảo trì");
        transitions.to(b, BookingStatus.CHECKED_IN, "HOST", ownerId, "Check-in");
        b.setActualCheckIn(now);
        reminders.cancelForBooking(bookingId);
        checkinRequests.closeOpenForBooking(bookingId);
        outbox.publish("BOOKING_CHECKED_IN:" + bookingId, "BOOKING_CHECKED_IN", "BOOKING", bookingId, Map.of("bookingId", bookingId));
        analytics.record("CHECKIN_COMPLETED:" + bookingId, "CHECKIN_COMPLETED", b.getPropertyId(), "u" + b.getCustomerId(), String.valueOf(bookingId), null);
        audit.record("BOOKING_CHECKIN", "BOOKING", bookingId, "CONFIRMED", "CHECKED_IN", null, null);
    }

    @Transactional
    public void checkOut(Long ownerId, Long bookingId) {
        Scoped s = lockScoped(ownerId, bookingId);
        Booking b = s.booking();
        if (b.getStatus() == BookingStatus.COMPLETED) return;
        if (b.getStatus() != BookingStatus.CHECKED_IN) {
            // check-out khi chưa check-in chỉ SUPPORT/ADMIN qua quy trình ngoại lệ có lý do
            throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Chỉ check-out booking đã CHECKED_IN");
        }
        transitions.to(b, BookingStatus.COMPLETED, "HOST", ownerId, "Check-out");
        b.setActualCheckOut(clock.instant());
        outbox.publish("BOOKING_COMPLETED:" + bookingId, "BOOKING_COMPLETED", "BOOKING", bookingId, Map.of("bookingId", bookingId));
        audit.record("BOOKING_CHECKOUT", "BOOKING", bookingId, "CHECKED_IN", "COMPLETED", null, null);
    }

    /** UC-29 bước 8: sau cutoff cấu hình, HOST đánh dấu vắng mặt qua quy trình riêng có audit. Tiền đã thu được giữ theo chính sách. */
    @Transactional
    public void noShow(Long ownerId, Long bookingId, String reason) {
        Scoped s = lockScoped(ownerId, bookingId);
        Booking b = s.booking();
        if (b.getStatus() != BookingStatus.CONFIRMED) throw BusinessException.conflict("Chỉ đánh dấu NO_SHOW cho booking CONFIRMED");
        Instant cutoff = pricing.scheduledCheckIn(s.property(), b.getCheckIn()).plus(Duration.ofHours(config.getInt("stay.noShow.afterHours")));
        if (clock.instant().isBefore(cutoff)) throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Chưa qua thời điểm cho phép đánh dấu vắng mặt");
        transitions.to(b, BookingStatus.NO_SHOW, "HOST", ownerId, reason);
        transitions.release(bookingId);
        reminders.cancelForBooking(bookingId);
        checkinRequests.closeOpenForBooking(bookingId);
        outbox.publish("BOOKING_NO_SHOW:" + bookingId, "BOOKING_NO_SHOW", "BOOKING", bookingId, Map.of("bookingId", bookingId));
        audit.record("BOOKING_NO_SHOW", "BOOKING", bookingId, "CONFIRMED", "NO_SHOW", reason, null);
    }

    /**
     * UC-15 luồng ngoại lệ: check-out khi booking CHƯA check-in (khách đã ở nhưng lễ tân quên check-in, sự cố hệ thống...).
     * Chỉ SUPPORT có quyền SUPPORT_STAY_EXCEPTION, bắt buộc lý do, có audit. Đi qua state machine bình thường
     * (CONFIRMED -> CHECKED_IN -> COMPLETED) với actor SUPPORT; actual_check_in giữ null để phân biệt với check-in thật.
     * Không thay đổi tổng tiền đã xác nhận. Request lặp không cập nhật hai lần.
     */
    @Transactional
    public void supportForceCheckout(Long supportId, Long bookingId, String reason) {
        if (reason == null || reason.isBlank()) throw BusinessException.validation("Cần nêu lý do xử lý ngoại lệ");
        Booking b = bookings.lockById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));
        if (b.getStatus() == BookingStatus.COMPLETED) return;
        if (b.getStatus() != BookingStatus.CONFIRMED && b.getStatus() != BookingStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.CHECKIN_NOT_ALLOWED, "Booking " + b.getStatus() + " không thể check-out ngoại lệ");
        }
        String from = b.getStatus().name();
        if (b.getStatus() == BookingStatus.CONFIRMED) {
            transitions.to(b, BookingStatus.CHECKED_IN, "SUPPORT", supportId, "Ngoại lệ (không có check-in thực tế): " + reason);
        }
        transitions.to(b, BookingStatus.COMPLETED, "SUPPORT", supportId, "Check-out ngoại lệ: " + reason);
        b.setActualCheckOut(clock.instant());
        reminders.cancelForBooking(bookingId);
        checkinRequests.closeOpenForBooking(bookingId);
        outbox.publish("BOOKING_COMPLETED:" + bookingId, "BOOKING_COMPLETED", "BOOKING", bookingId, Map.of("bookingId", bookingId));
        audit.record("SUPPORT_FORCE_CHECKOUT", "BOOKING", bookingId, from, "COMPLETED", reason, null);
    }
}
