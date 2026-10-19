package datphong.vn.exception.entity;

import datphong.vn.constant.ConversationType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "property_id", nullable = false)
    private Long propertyId;
    @Column(name = "booking_id")
    private Long bookingId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "host_id", nullable = false)
    private Long hostId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConversationType type;
    @Column(nullable = false, length = 20)
    private String status = "OPEN";
    @Column(name = "last_message_seq", nullable = false)
    private long lastMessageSeq;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Version
    private long version;
}
