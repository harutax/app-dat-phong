package datphong.vn.exception.entity;

import datphong.vn.constant.BookingStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "booking_status_history")
@Getter
@Setter
@NoArgsConstructor
public class BookingStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private BookingStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private BookingStatus toStatus;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;
    @Column(length = 500)
    private String reason;
    @Column(name = "correlation_id", length = 64)
    private String correlationId;
    @Column(nullable = false)
    private Instant at;
}
