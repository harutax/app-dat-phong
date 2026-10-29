package datphong.vn.service;

import datphong.vn.constant.BookingStatus;
import datphong.vn.constant.PropertyStatus;
import datphong.vn.constant.RoomStatus;
import datphong.vn.domain.PricingCalculator;
import datphong.vn.domain.StayPeriod;
import datphong.vn.dto.request.BookingRequests;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import datphong.vn.util.Texts;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * UC-09. MỘT transaction ngắn, chỉ bao dữ liệu PostgreSQL (không gọi Redis/MinIO/Fake Bank/SMTP trong đây).
 *
 * Cơ chế tồn kho THỐNG NHẤT cho toàn hệ thống:
 *   (1) khóa hàng rooms (PESSIMISTIC_WRITE)  ->  (2) kiểm tra overlap trên room_allocations  ->  (3) insert allocation.
 * Exclusion constraint ex_room_no_overlap là chốt chặn cuối nếu có đường nào đó bỏ qua bước (1).
 * Không dùng "SELECT rồi INSERT" đơn lẻ; không dựa Redis lock.
 */
@Service
@RequiredArgsConstructor
public class BookingCreator {

    private final RoomRepository rooms;
    private final PropertyRepository properties;
    private final RoomTypeRepository roomTypes;
    private final BookingRepository bookings;
    private final BookingNightRepository nights;
    private final RoomAllocationRepository allocations;
    private final PricingService pricing;
    private final ConfigService config;
    private final IdempotencyService idempotency;
    private final BookingTransitions transitions;
    private final OutboxService outbox;
    private final MeterRegistry metrics;
    private final Clock clock;

    public record Result(Booking booking, boolean replayed) {}

    @Transactional
    public Result create(Long customerId, BookingRequests.Create req, String requestHash) {
        String scope = "booking:create:" + customerId;

        // Idempotency kiểm tra TRƯỚC mọi validate: cùng key + cùng payload luôn trả lại kết quả cũ,
        // kể cả khi sau đó phòng/cơ sở đã đổi trạng thái hoặc ngày nhận phòng đã qua.
        var replay = idempotency.lookup(scope, req.idempotencyKey(), requestHash);
        if (replay.isPresent()) return new Result(bookings.findById(Long.valueOf(replay.get())).orElseThrow(), true);

        Room room = rooms.findById(req.roomId()).orElseThrow(() -> BusinessException.notFound("Phòng"));
        Property property = properties.findById(room.getPropertyId()).orElseThrow(() -> BusinessException.notFound("Phòng"));
        RoomType type = roomTypes.findById(room.getRoomTypeId()).orElseThrow();
        if (property.getStatus() != PropertyStatus.PUBLISHED) throw new BusinessException(ErrorCode.ROOM_UNAVAILABLE, "Cơ sở không mở bán");
        StayPeriod period = pricing.validateStay(property, type, req.checkIn(), req.checkOut(), req.guestCount());

        // (1) khóa phòng - các request cạnh tranh cùng phòng được tuần tự hóa tại đây
        Room locked = rooms.lockById(req.roomId()).orElseThrow(() -> BusinessException.notFound("Phòng"));
        // Re-check sau khi lấy khóa: request trùng key có thể vừa commit trong lúc ta chờ khóa; trạng thái phòng có thể vừa đổi.
        replay = idempotency.lookup(scope, req.idempotencyKey(), requestHash);
        if (replay.isPresent()) return new Result(bookings.findById(Long.valueOf(replay.get())).orElseThrow(), true);
        if (locked.getStatus() != RoomStatus.ACTIVE || type.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ROOM_UNAVAILABLE, "Phòng không còn mở bán");
        }

        // (2) kiểm tra chồng lấn dưới khóa
        if (allocations.existsOverlap(locked.getId(), period.checkIn(), period.checkOut())) {
            metrics.counter("booking_conflict_total").increment();
            throw new BusinessException(ErrorCode.ROOM_UNAVAILABLE, "Phòng đã được đặt hoặc đang được giữ cho khoảng ngày này");
        }

        // Giá/chính sách hiện hành đọc TRONG transaction, server tự tính tiền
        PricingCalculator.Quote q = pricing.price(type, period);
        String currentPriceVersion = pricing.priceVersion(q);
        boolean totalChanged = req.expectedTotal() != null && req.expectedTotal().compareTo(q.total()) != 0;
        boolean versionChanged = req.priceVersion() != null && !req.priceVersion().isBlank() && !req.priceVersion().equals(currentPriceVersion);
        if (totalChanged || versionChanged) {
            throw new BusinessException(ErrorCode.PRICE_CHANGED, "Giá đã thay đổi so với báo giá",
                    Map.of("newTotal", q.total(), "priceVersion", currentPriceVersion));
        }

        Booking b = new Booking();
        b.setBookingCode(newCode());
        b.setCustomerId(customerId);
        b.setPropertyId(property.getId());
        b.setRoomId(locked.getId());
        b.setRoomTypeId(type.getId());
        b.setCheckIn(period.checkIn());
        b.setCheckOut(period.checkOut());
        b.setGuestCount(req.guestCount());
        b.setStatus(BookingStatus.PENDING_PAYMENT);
        b.setExpiresAt(clock.instant().plus(Duration.ofMinutes(config.getInt("booking.hold.minutes"))));
        b.setSubtotal(q.subtotal());
        b.setTaxAmount(q.tax());
        b.setTotalAmount(q.total());
        b.setCurrency(q.currency());
        // snapshot chính sách hủy: sau này HOST sửa chính sách không ảnh hưởng booking này
        b.setFreeCancelDeadline(pricing.freeCancelDeadline(property, period.checkIn()));
        b.setPenaltyPercent(property.getPenaltyPercent());
        b.setSpecialNote(req.specialNote());
        bookings.saveAndFlush(b);

        // snapshot giá từng đêm; lỗi ở bước này rollback TOÀN BỘ (booking + hold + idempotency)
        nights.saveAll(q.nights().stream().map(n -> new BookingNight(b.getId(), n.stayDate(), n.amount())).toList());

        // (3) allocation - exclusion constraint là chốt cuối
        RoomAllocation a = new RoomAllocation();
        a.setRoomId(locked.getId());
        a.setBookingId(b.getId());
        a.setCheckIn(period.checkIn());
        a.setCheckOut(period.checkOut());
        a.setActive(true);
        a.setCreatedAt(clock.instant());
        allocations.saveAndFlush(a);

        transitions.record(b.getId(), null, BookingStatus.PENDING_PAYMENT, "CUSTOMER", customerId, "Tạo booking và giữ chỗ");
        idempotency.store(scope, req.idempotencyKey(), requestHash, String.valueOf(b.getId()));
        outbox.publish("BOOKING_CREATED:" + b.getId(), "BOOKING_CREATED", "BOOKING", b.getId(),
                Map.of("bookingId", b.getId(), "customerId", customerId, "propertyId", property.getId(), "bookingCode", b.getBookingCode()));
        metrics.counter("booking_created_total").increment();
        return new Result(b, false);
    }

    private String newCode() {
        for (int i = 0; i < 5; i++) {
            String c = Texts.code("BK", 8);
            if (!bookings.existsByBookingCode(c)) return c;
        }
        return Texts.code("BK", 12); // unique constraint vẫn là chốt cuối
    }

}
