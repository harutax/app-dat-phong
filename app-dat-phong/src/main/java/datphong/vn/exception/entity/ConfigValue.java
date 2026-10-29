package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "config_values")
@Getter
@Setter
@NoArgsConstructor
public class ConfigValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "cfg_key", nullable = false, length = 100)
    private String key;
    @Column(nullable = false, length = 20)
    private String scope = "GLOBAL";
    @Column(name = "scope_id", nullable = false, length = 50)
    private String scopeId = "";
    @Column(name = "cfg_value", nullable = false, length = 500)
    private String value;
    /** Giá trị đang hiệu lực trước khi value (lên lịch ở tương lai) có hiệu lực; null = dùng default. */
    @Column(name = "previous_value", length = 500)
    private String previousValue;
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;
    @Column(nullable = false)
    private int version = 1;
    @Column(name = "updated_by")
    private Long updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
