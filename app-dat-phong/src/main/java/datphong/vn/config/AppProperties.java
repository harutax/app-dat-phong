package datphong.vn.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình ứng dụng (prefix app). Secret luôn đến từ biến môi trường, không nằm trong source. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String defaultTimezone = "Asia/Ho_Chi_Minh";
    private String publicBaseUrl = "http://localhost:3000";
    private String verificationSecret;
    private String metricsToken = "";
    private String redisPrefix = "datphong:";
    private Scheduler scheduler = new Scheduler();
    private Mail mail = new Mail();
    private FakeBank fakeBank = new FakeBank();
    private Seed seed = new Seed();

    @Getter
    @Setter
    public static class Scheduler {
        private boolean enabled = true;
        private long expiryFixedDelayMs = 15_000;
    }

    @Getter
    @Setter
    public static class Mail {
        private boolean enabled = true;
        private String from = "no-reply@datphong.local";
    }

    @Getter
    @Setter
    public static class FakeBank {
        private String baseUrl;
        private String merchantId = "datphong";
        private String serviceSecret;
        private String webhookSecret;
        private int webhookReplayWindowSeconds = 300;
        private int httpTimeoutMs = 4000;
    }

    @Getter
    @Setter
    public static class Seed {
        private boolean enabled;
        private String password = "ChangeMe@12345";
    }
}
