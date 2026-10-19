package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "email_templates")
@Getter
@Setter
@NoArgsConstructor
public class EmailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "template_code", nullable = false, length = 60)
    private String templateCode;
    @Column(nullable = false, length = 10)
    private String locale = "vi";
    @Column(name = "active_version")
    private Integer activeVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
