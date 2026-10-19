package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "scheduled_reminders")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledReminder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;
    @Column(name = "reminder_type", nullable = false, length = 30)
    private String reminderType;
    @Column(name = "window_code", nullable = false, length = 30)
    private String windowCode;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;
    /** PENDING / QUEUED / CANCELLED / SKIPPED */
    @Column(nullable = false, length = 15)
    private String status = "PENDING";
    @Column(name = "mail_event_id")
    private Long mailEventId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
