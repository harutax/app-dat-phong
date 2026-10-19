package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Version đã PUBLISH là bất biến (ACTIVE/ARCHIVED); chỉ DRAFT mới sửa được. */
@Entity
@Table(name = "email_template_versions")
@Getter
@Setter
@NoArgsConstructor
public class EmailTemplateVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "template_id", nullable = false)
    private Long templateId;
    @Column(nullable = false)
    private int version;
    @Column(name = "subject_template", nullable = false, length = 300)
    private String subjectTemplate;
    @Column(name = "html_body", nullable = false, columnDefinition = "text")
    private String htmlBody;
    @Column(name = "text_body", nullable = false, columnDefinition = "text")
    private String textBody;
    @Column(name = "allowed_variables", nullable = false, length = 500)
    private String allowedVariables;
    @Column(nullable = false, length = 15)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
