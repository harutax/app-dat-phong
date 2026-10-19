package datphong.vn.exception.entity;

import datphong.vn.constant.RoomStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Phòng vật lý: đơn vị tồn kho. Hàng này bị khóa PESSIMISTIC_WRITE khi giữ chỗ. */
@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "property_id", nullable = false)
    private Long propertyId;
    @Column(name = "room_type_id", nullable = false)
    private Long roomTypeId;
    @Column(name = "room_code", nullable = false, length = 30)
    private String roomCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomStatus status = RoomStatus.ACTIVE;
    @Version
    private long version;
}
