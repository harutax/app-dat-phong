package datphong.vn.exception.entity;

import datphong.vn.constant.BookingStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Đơn đặt phòng. Giá (booking_nights) và chính sách hủy (free_cancel_deadline, penalty_percent)
 * là SNAPSHOT tại thời điểm đặt - không diễn giải lại theo cấu hình mới.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_code", nullable = false, unique = true, length = 20)
    private String bookingCode;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "property_id", nullable = false)
    private Long propertyId;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "room_type_id", nullable = false)
    private Long roomTypeId;
    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;
    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;
    @Column(name = "guest_count", nullable = false)
    private int guestCount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingStatus status;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;
    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 3)
    private String currency = "VND";
    @Column(name = "free_cancel_deadline", nullable = false)
    private Instant freeCancelDeadline;
    @Column(name = "penalty_percent", nullable = false)
    private int penaltyPercent;
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
    @Column(name = "cancellation_fee", precision = 14, scale = 2)
    private BigDecimal cancellationFee;
    @Column(name = "refundable_amount", precision = 14, scale = 2)
    private BigDecimal refundableAmount;
    @Column(name = "actual_check_in")
    private Instant actualCheckIn;
    @Column(name = "actual_check_out")
    private Instant actualCheckOut;
    @Column(name = "special_note", length = 1000)
    private String specialNote;
    /** UC-14/29: giờ đến dự kiến (giờ địa phương của cơ sở) do HOST/khách thống nhất. */
    @Column(name = "expected_arrival_time")
    private LocalTime expectedArrivalTime;
    /** UC-14: ghi chú nội bộ của HOST - chỉ HOST/SUPPORT xem, không trả cho khách. */
    @Column(name = "host_note", length = 1000)
    private String hostNote;
    @Column(name = "source_channel", nullable = false, length = 30)
    private String sourceChannel = "WEB";
    @Version
    private long version;
}
