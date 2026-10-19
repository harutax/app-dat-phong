package datphong.vn.exception.entity;

import datphong.vn.constant.PropertyStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/** Cơ sở lưu trú. ownerId luôn lấy từ JWT, timezone là múi giờ nghiệp vụ của cơ sở. */
@Entity
@Table(name = "properties")
@Getter
@Setter
@NoArgsConstructor
public class Property extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(columnDefinition = "text")
    private String description;
    @Column(name = "property_type", nullable = false, length = 30)
    private String propertyType = "HOTEL";
    private String address;
    private String city;
    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;
    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;
    @Column(nullable = false, length = 50)
    private String timezone = "Asia/Ho_Chi_Minh";
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PropertyStatus status = PropertyStatus.DRAFT;
    @Column(name = "check_in_time", nullable = false)
    private LocalTime checkInTime = LocalTime.of(14, 0);
    @Column(name = "check_out_time", nullable = false)
    private LocalTime checkOutTime = LocalTime.of(12, 0);
    @Column(name = "free_cancel_hours", nullable = false)
    private int freeCancelHours = 48;
    @Column(name = "penalty_percent", nullable = false)
    private int penaltyPercent = 50;
    @Column(name = "contact_phone", length = 30)
    private String contactPhone;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "cover_media_id")
    private Long coverMediaId;
    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "property_amenities",
            joinColumns = @JoinColumn(name = "property_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    private Set<Amenity> amenities = new HashSet<>();
}
