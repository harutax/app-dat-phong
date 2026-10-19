package fakebank.vn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Giao webhook at-least-once từ outbox: claim SKIP LOCKED, ký HMAC, retry backoff. Hệ thống đặt phòng phải tự dedupe theo eventId.
 * Outbox được ghi cùng transaction bank nên không có webhook cho giao dịch đã rollback.
 */
@Component
public class WebhookDispatcher {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String url;
    private final String secret;

    public WebhookDispatcher(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txm,
                             @Value("${bank.webhook-url}") String url, @Value("${bank.webhook-secret}") String secret) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.url = url;
        this.secret = secret;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    public void deliver() {
        for (int i = 0; i < 20; i++) {
            Boolean any = tx.execute(s -> {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM bank_webhook_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now "
                        + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", new MapSqlParameterSource("now", Timestamp.from(Instant.now())));
                if (rows.isEmpty()) return false;
                Map<String, Object> r = rows.get(0);
                long id = ((Number) r.get("id")).longValue();
                int attempts = ((Number) r.get("attempts")).intValue() + 1;
                String err = null;
                try {
                    String body = (String) r.get("payload");
                    String ts = String.valueOf(System.currentTimeMillis() / 1000);
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json").header("X-Timestamp", ts)
                            .header("X-Signature", Crypto.hmac(secret, ts + "." + body))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() / 100 != 2) err = "HTTP " + res.statusCode();
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                if (err == null) {
                    jdbc.update("UPDATE bank_webhook_outbox SET status = 'DONE', attempts = :a WHERE id = :id", new MapSqlParameterSource("a", attempts).addValue("id", id));
                } else if (attempts >= 10) {
                    jdbc.update("UPDATE bank_webhook_outbox SET status = 'FAILED', attempts = :a, last_error = :e WHERE id = :id",
                            new MapSqlParameterSource("a", attempts).addValue("e", err).addValue("id", id));
                } else {
                    jdbc.update("UPDATE bank_webhook_outbox SET attempts = :a, last_error = :e, next_attempt_at = :at WHERE id = :id",
                            new MapSqlParameterSource("a", attempts).addValue("e", err)
                                    .addValue("at", Timestamp.from(Instant.now().plusSeconds(Math.min(300, 5L << Math.min(attempts, 6))))).addValue("id", id));
                }
                return true;
            });
            if (!Boolean.TRUE.equals(any)) break;
        }
    }
}
