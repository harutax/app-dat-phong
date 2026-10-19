package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "config_change_logs")
@Getter
@Setter
@NoArgsConstructor
public class ConfigChangeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "cfg_key", nullable = false, length = 100)
    private String key;
    @Column(name = "old_value", length = 500)
    private String oldValue;
    @Column(name = "new_value", nullable = false, length = 500)
    private String newValue;
    @Column(nullable = false)
    private int version;
    @Column(name = "changed_by")
    private Long changedBy;
    @Column(length = 300)
    private String reason;
    @Column(nullable = false)
    private Instant at;
}
