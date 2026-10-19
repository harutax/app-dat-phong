package fakebank.vn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Service authentication: HMAC(service-secret, timestamp + "." + body | pathAndQuery) + replay window. Không dùng thẻ/ngân hàng thật. */
@RestController
@RequestMapping("/fakebank/api/v1")
public class BankController {

    private final BankService bank;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String serviceSecret;
    private final String adminToken;
    private final long replayWindow;

    public BankController(BankService bank, @Value("${bank.service-secret}") String serviceSecret,
                          @Value("${bank.admin-token}") String adminToken, @Value("${bank.replay-window-seconds}") long replayWindow) {
        this.bank = bank;
        this.serviceSecret = serviceSecret;
        this.adminToken = adminToken;
        this.replayWindow = replayWindow;
    }

    private void auth(String ts, String sig, String signedData) {
        long t;
        try { t = Long.parseLong(ts); } catch (Exception e) { throw new BankException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "timestamp"); }
        if (Math.abs(System.currentTimeMillis() / 1000 - t) > replayWindow) throw new BankException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "replay window");
        if (!Crypto.verify(serviceSecret, ts + "." + signedData, sig)) throw new BankException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "signature");
    }

    @PostMapping("/transactions/debit")
    public Map<String, Object> debit(@RequestBody String raw, @RequestHeader("X-Timestamp") String ts, @RequestHeader("X-Signature") String sig) throws Exception {
        auth(ts, sig, raw);
        @SuppressWarnings("unchecked") Map<String, Object> req = mapper.readValue(raw, Map.class);
        return bank.debit(req);
    }

    @GetMapping("/transactions/by-key")
    public ResponseEntity<?> byKey(@RequestParam String merchantId, @RequestParam String idempotencyKey,
                                   @RequestHeader("X-Timestamp") String ts, @RequestHeader("X-Signature") String sig,
                                   jakarta.servlet.http.HttpServletRequest http) {
        auth(ts, sig, http.getRequestURI().replaceFirst("^.*?(/fakebank)", "$1") + "?" + http.getQueryString());
        return bank.lookupByKey(merchantId, idempotencyKey).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")));
    }

    @PostMapping("/refunds")
    public Map<String, Object> refund(@RequestBody String raw, @RequestHeader("X-Timestamp") String ts, @RequestHeader("X-Signature") String sig) throws Exception {
        auth(ts, sig, raw);
        @SuppressWarnings("unchecked") Map<String, Object> req = mapper.readValue(raw, Map.class);
        return bank.refund(req);
    }

    @GetMapping("/refunds/by-key")
    public ResponseEntity<?> refundByKey(@RequestParam String merchantId, @RequestParam String refundKey,
                                         @RequestHeader("X-Timestamp") String ts, @RequestHeader("X-Signature") String sig,
                                         jakarta.servlet.http.HttpServletRequest http) {
        auth(ts, sig, http.getRequestURI().replaceFirst("^.*?(/fakebank)", "$1") + "?" + http.getQueryString());
        return bank.lookupRefund(merchantId, refundKey).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")));
    }

    // ---- admin (kịch bản lỗi, số dư giả lập) - X-Admin-Token
    private void admin(String token) {
        if (token == null || !java.security.MessageDigest.isEqual(token.getBytes(), adminToken.getBytes())) throw new BankException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "admin token");
    }

    @GetMapping("/admin/accounts")
    public List<Map<String, Object>> accounts(@RequestHeader("X-Admin-Token") String token) {
        admin(token);
        return bank.accounts();
    }

    @PutMapping("/admin/accounts/{no}/scenario")
    public Map<String, String> scenario(@PathVariable String no, @RequestParam String value, @RequestHeader("X-Admin-Token") String token) {
        admin(token);
        bank.setScenario(no, value);
        return Map.of("account", no, "scenario", value);
    }

    @PostMapping("/admin/accounts/{no}/topup")
    public Map<String, String> topUp(@PathVariable String no, @RequestParam BigDecimal amount, @RequestHeader("X-Admin-Token") String token) {
        admin(token);
        bank.topUp(no, amount);
        return Map.of("status", "OK");
    }

    @GetMapping("/admin/ledger-check")
    public Map<String, Object> ledger(@RequestHeader("X-Admin-Token") String token) {
        admin(token);
        return bank.ledgerCheck();
    }

    @ExceptionHandler(BankException.class)
    public ResponseEntity<Map<String, Object>> handle(BankException e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", "BANK_INTERNAL_ERROR", "message", "Lỗi nội bộ phía bank"));
    }
}
