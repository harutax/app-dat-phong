package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "booking_nights")
@Getter
@Setter
@NoArgsConstructor
public class BookingNight {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;
    @Column(name = "nightly_rate", nullable = false, precision = 14, scale = 2)
    private BigDecimal nightlyRate;

    public BookingNight(Long bookingId, LocalDate stayDate, BigDecimal nightlyRate) {
        this.bookingId = bookingId;
        this.stayDate = stayDate;
        this.nightlyRate = nightlyRate;
    }
}
