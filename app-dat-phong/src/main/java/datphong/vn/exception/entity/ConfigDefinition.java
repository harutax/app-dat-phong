package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "config_definitions")
@Getter
@Setter
@NoArgsConstructor
public class ConfigDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "cfg_key", nullable = false, unique = true, length = 100)
    private String key;
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;
    @Column(name = "min_value", precision = 14, scale = 2)
    private BigDecimal minValue;
    @Column(name = "max_value", precision = 14, scale = 2)
    private BigDecimal maxValue;
    @Column(name = "default_value", nullable = false, length = 500)
    private String defaultValue;
    @Column(nullable = false, length = 20)
    private String scope = "GLOBAL";
    @Column(name = "public_flag", nullable = false)
    private boolean publicFlag;
    @Column(length = 300)
    private String description;
}
