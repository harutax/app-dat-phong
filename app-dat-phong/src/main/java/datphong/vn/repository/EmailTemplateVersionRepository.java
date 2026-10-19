package datphong.vn.repository;

import datphong.vn.exception.entity.EmailTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateVersionRepository extends JpaRepository<EmailTemplateVersion, Long> {
    Optional<EmailTemplateVersion> findByTemplateIdAndVersion(Long templateId, int version);

    List<EmailTemplateVersion> findByTemplateIdOrderByVersionDesc(Long templateId);

    @Query("select coalesce(max(v.version), 0) from EmailTemplateVersion v where v.templateId = :tid")
    int maxVersion(@Param("tid") Long templateId);
}
