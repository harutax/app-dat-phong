package datphong.vn.exception.entity;

import datphong.vn.constant.ReviewStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
public class Review extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;
    @Column(name = "property_id", nullable = false)
    private Long propertyId;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private short rating;
    @Column(columnDefinition = "text")
    private String comment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewStatus status;
    @Column(name = "host_reply", columnDefinition = "text")
    private String hostReply;
    @Column(name = "host_replied_at")
    private Instant hostRepliedAt;
    @Column(nullable = false)
    private boolean reported;
    @Column(name = "moderation_note", length = 500)
    private String moderationNote;
    @Version
    private long version;
}
