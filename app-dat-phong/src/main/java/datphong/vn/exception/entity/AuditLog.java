package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Append-only: không có API sửa/xóa. outcome phân biệt ATTEMPT / SUCCESS / FAILURE. */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "actor_roles", length = 100)
    private String actorRoles;
    @Column(nullable = false, length = 80)
    private String action;
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;
    @Column(name = "entity_id", length = 60)
    private String entityId;
    @Column(name = "request_id", length = 64)
    private String requestId;
    @Column(name = "from_state", length = 60)
    private String fromState;
    @Column(name = "to_state", length = 60)
    private String toState;
    @Column(length = 500)
    private String reason;
    @Column(columnDefinition = "text")
    private String details;
    @Column(nullable = false, length = 15)
    private String outcome = "SUCCESS";
    @Column(nullable = false)
    private Instant at;
}
