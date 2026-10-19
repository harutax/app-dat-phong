package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Chiếm dụng phòng theo khoảng [check_in, check_out). Cột stay_range (daterange) là generated column ở DB
 * nên KHÔNG ánh xạ; exclusion constraint ex_room_no_overlap ngăn hai allocation active giao nhau.
 */
@Entity
@Table(name = "room_allocations")
@Getter
@Setter
@NoArgsConstructor
public class RoomAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;
    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;
    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "released_at")
    private Instant releasedAt;
}
