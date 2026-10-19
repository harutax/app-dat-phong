package datphong.vn.service;

import datphong.vn.exception.entity.EmailTemplate;
import datphong.vn.exception.entity.EmailTemplateVersion;
import datphong.vn.repository.EmailTemplateRepository;
import datphong.vn.repository.EmailTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Seed phiên bản 1 (ACTIVE) cho mọi event email nếu chưa có; không đè template admin đã sửa. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTemplateSeeder implements ApplicationRunner {

    private final EmailTemplateRepository templates;
    private final EmailTemplateVersionRepository versions;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        TemplateCatalog.DEFAULTS.forEach((code, def) -> {
            if (templates.findByTemplateCodeAndLocale(code, "vi").isPresent()) return;
            EmailTemplate t = new EmailTemplate();
            t.setTemplateCode(code);
            t.setLocale("vi");
            t.setCreatedAt(clock.instant());
            t.setActiveVersion(1);
            templates.save(t);
            EmailTemplateVersion v = new EmailTemplateVersion();
            v.setTemplateId(t.getId());
            v.setVersion(1);
            v.setSubjectTemplate(def.subject());
            v.setTextBody(def.text());
            v.setHtmlBody(TemplateCatalog.htmlFromText(def.text()));
            v.setAllowedVariables(String.join(",", def.vars()));
            v.setStatus("ACTIVE");
            v.setPublishedAt(clock.instant());
            v.setCreatedAt(clock.instant());
            versions.save(v);
            log.info("seeded email template {}", code);
        });
    }
}
