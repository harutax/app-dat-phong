package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, length = 80)
    private String eventId;
    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;
    @Column(nullable = false, length = 60)
    private String type;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 1000)
    private String body;
    @Column(name = "object_type", length = 40)
    private String objectType;
    @Column(name = "object_id", length = 60)
    private String objectId;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
