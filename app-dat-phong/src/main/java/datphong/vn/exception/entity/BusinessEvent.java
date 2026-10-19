package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Event log analytics append-only, unique event_id để retry/gửi lặp không đếm trùng. */
@Entity
@Table(name = "business_events")
@Getter
@Setter
@NoArgsConstructor
public class BusinessEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 80)
    private String eventId;
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;
    @Column(name = "property_id")
    private Long propertyId;
    @Column(name = "session_key", length = 80)
    private String sessionKey;
    @Column(name = "ref_id", length = 60)
    private String refId;
    @Column(length = 60)
    private String code;
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;
}
