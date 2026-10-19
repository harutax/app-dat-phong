package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "room_rates")
@Getter
@Setter
@NoArgsConstructor
public class RoomRate extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "room_type_id", nullable = false)
    private Long roomTypeId;
    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency = "VND";
    @Version
    private long version;
}
