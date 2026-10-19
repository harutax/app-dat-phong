package datphong.vn.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AppConfig {

    /** Một nguồn thời gian duy nhất cho toàn hệ thống (dễ kiểm thử, tránh lệch giữa các luồng). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName())) return Optional.of("system");
            return Optional.of(a.getName());
        };
    }

    @Bean
    public MinioClient minioClient(@Value("${minio.url}") String url,
                                   @Value("${minio.access-key}") String ak,
                                   @Value("${minio.secret-key}") String sk) {
        return MinioClient.builder().endpoint(url).credentials(ak, sk).build();
    }
}
