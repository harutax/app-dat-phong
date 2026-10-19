package datphong.vn.client;

import datphong.vn.config.AppProperties;
import datphong.vn.domain.HmacSigner;
import datphong.vn.util.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client HTTP gọi Fake Bank (service độc lập, DB riêng). LUÔN gọi NGOÀI transaction DB.
 * Phân biệt ba loại kết quả:
 *  - trả về BankTxn với status cuối (SUCCEEDED / FAILED): chắc chắn;
 *  - trả về status ACCEPTED/PROCESSING: bank đã nhận, kết quả đến qua webhook/đối soát;
 *  - ném BankUnknownException (timeout/IO/5xx): KHÔNG biết bank đã trừ tiền hay chưa -> payment UNKNOWN, tuyệt đối không coi là FAILED.
 */
@Slf4j
@Component
public class FakeBankClient {

    public record BankTxn(String providerTxnId, String status, String failureCode, BigDecimal amount, String currency) {}

    public record BankRefund(String providerRef, String status, String failureCode) {}

    public static class BankUnknownException extends RuntimeException {
        public BankUnknownException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private final AppProperties.FakeBank cfg;
    private final HttpClient http;

    public FakeBankClient(AppProperties props) {
        this.cfg = props.getFakeBank();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(cfg.getHttpTimeoutMs())).build();
    }

    public BankTxn debit(String paymentIntentId, String bookingCode, BigDecimal amount, String currency, String idempotencyKey, String payerAccount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", cfg.getMerchantId());
        body.put("paymentIntentId", paymentIntentId);
        body.put("merchantReference", bookingCode);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("idempotencyKey", idempotencyKey);
        body.put("payerAccount", payerAccount);
        JsonNodeLite r = post("/fakebank/api/v1/transactions/debit", Json.write(body));
        if (r.status >= 400 && r.status < 500) {
            Map<String, Object> m = safeMap(r.body);
            if (r.status == 409) throw new BankUnknownException("Idempotency conflict phía bank: " + m.get("code"), null);
            return new BankTxn(null, "FAILED", String.valueOf(m.getOrDefault("code", "BANK_REJECTED")), amount, currency);
        }
        return toTxn(safeMap(r.body));
    }

    /** Truy vấn theo idempotencyKey để đối soát; empty nếu bank không có giao dịch này. */
    public Optional<BankTxn> lookup(String idempotencyKey) {
        JsonNodeLite r = get("/fakebank/api/v1/transactions/by-key?merchantId=" + enc(cfg.getMerchantId()) + "&idempotencyKey=" + enc(idempotencyKey));
        if (r.status == 404) return Optional.empty();
        if (r.status >= 400) throw new BankUnknownException("Bank trả " + r.status, null);
        return Optional.of(toTxn(safeMap(r.body)));
    }

    public BankRefund refund(String refundKey, String providerTxnId, BigDecimal amount, String currency, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", cfg.getMerchantId());
        body.put("refundKey", refundKey);
        body.put("providerTxnId", providerTxnId);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reason", reason);
        JsonNodeLite r = post("/fakebank/api/v1/refunds", Json.write(body));
        Map<String, Object> m = safeMap(r.body);
        if (r.status >= 400 && r.status < 500 && r.status != 409) {
            return new BankRefund(null, "FAILED", String.valueOf(m.getOrDefault("code", "BANK_REJECTED")));
        }
        if (r.status >= 400) throw new BankUnknownException("Bank trả " + r.status, null);
        return new BankRefund((String) m.get("providerRef"), String.valueOf(m.get("status")), (String) m.get("failureCode"));
    }

    public Optional<BankRefund> lookupRefund(String refundKey) {
        JsonNodeLite r = get("/fakebank/api/v1/refunds/by-key?merchantId=" + enc(cfg.getMerchantId()) + "&refundKey=" + enc(refundKey));
        if (r.status == 404) return Optional.empty();
        if (r.status >= 400) throw new BankUnknownException("Bank trả " + r.status, null);
        Map<String, Object> m = safeMap(r.body);
        return Optional.of(new BankRefund((String) m.get("providerRef"), String.valueOf(m.get("status")), (String) m.get("failureCode")));
    }

    // ------------------------------------------------------------------ http
    private record JsonNodeLite(int status, String body) {}

    private JsonNodeLite post(String path, String body) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + path))
                .timeout(Duration.ofMillis(cfg.getHttpTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Id", cfg.getMerchantId())
                .header("X-Timestamp", ts)
                .header("X-Signature", HmacSigner.sign(cfg.getServiceSecret(), ts + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return send(req);
    }

    private JsonNodeLite get(String pathAndQuery) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + pathAndQuery))
                .timeout(Duration.ofMillis(cfg.getHttpTimeoutMs()))
                .header("X-Merchant-Id", cfg.getMerchantId())
                .header("X-Timestamp", ts)
                .header("X-Signature", HmacSigner.sign(cfg.getServiceSecret(), ts + "." + pathAndQuery))
                .GET().build();
        return send(req);
    }

    private JsonNodeLite send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 500) throw new BankUnknownException("Bank lỗi " + res.statusCode(), null);
            return new JsonNodeLite(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new BankUnknownException("Không nhận được phản hồi từ bank: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankUnknownException("Bị ngắt khi gọi bank", e);
        }
    }

    private static BankTxn toTxn(Map<String, Object> m) {
        Object amt = m.get("amount");
        return new BankTxn((String) m.get("providerTxnId"), String.valueOf(m.get("status")), (String) m.get("failureCode"),
                amt == null ? null : new BigDecimal(String.valueOf(amt)), (String) m.get("currency"));
    }

    private static Map<String, Object> safeMap(String body) {
        try {
            return body == null || body.isBlank() ? Map.of() : Json.readMap(body);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
