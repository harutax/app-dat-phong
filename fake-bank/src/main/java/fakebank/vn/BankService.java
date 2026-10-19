package fakebank.vn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Lõi Fake Bank. Mỗi thao tác tiền là MỘT transaction cục bộ của DB bank:
 *   khóa hàng tài khoản (FOR UPDATE, theo thứ tự id tăng dần để tránh deadlock) -> kiểm tra số dư -> cập nhật số dư
 *   -> ghi 2 bút toán nợ/có cân bằng -> ghi trạng thái giao dịch -> ghi webhook vào outbox. Tất cả cùng commit hoặc cùng rollback.
 * Bất biến: (1) tổng nợ = tổng có; (2) một (merchant, idempotency_key) không bị trừ hai lần; (3) số dư >= 0.
 */
@Service
public class BankService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String merchantId;
    private final long timeoutDelayMs;
    private final long delayedCallbackSeconds;

    public BankService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txm,
                       @Value("${bank.merchant-id}") String merchantId,
                       @Value("${bank.timeout-scenario-delay-ms}") long timeoutDelayMs,
                       @Value("${bank.delayed-callback-seconds}") long delayedCallbackSeconds) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.merchantId = merchantId;
        this.timeoutDelayMs = timeoutDelayMs;
        this.delayedCallbackSeconds = delayedCallbackSeconds;
    }

    // ------------------------------------------------------------------ DEBIT
    public Map<String, Object> debit(Map<String, Object> req) {
        String merchant = str(req, "merchantId");
        if (!merchantId.equals(merchant)) throw new BankException(HttpStatus.FORBIDDEN, "UNKNOWN_MERCHANT", "Merchant không hợp lệ");
        String key = str(req, "idempotencyKey");
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        String currency = str(req, "currency");
        String payer = str(req, "payerAccount");
        if (amount.signum() <= 0) throw new BankException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Số tiền phải > 0");
        String hash = Crypto.sha256(merchant + "|" + str(req, "paymentIntentId") + "|" + amount.stripTrailingZeros().toPlainString() + "|" + currency + "|" + payer);

        // Cùng key + cùng payload -> trả giao dịch cũ; cùng key + khác payload -> IDEMPOTENCY_CONFLICT.
        Optional<Map<String, Object>> existing = findTxn(merchant, key);
        if (existing.isPresent()) return replay(existing.get(), hash);

        Map<String, Object> account = jdbc.queryForList("SELECT * FROM bank_accounts WHERE account_no = :n AND merchant = FALSE",
                new MapSqlParameterSource("n", payer)).stream().findFirst().orElse(null);
        String scenario = account == null ? "SUCCESS" : (String) account.get("scenario");
        String providerTxnId = "FB" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        Instant now = Instant.now();

        Map<String, Object> result;
        try {
            result = tx.execute(s -> {
                if ("DB_OUTAGE".equals(scenario)) throw new IllegalStateException("Mô phỏng lỗi DB phía bank (rollback toàn bộ)");
                // INITIATED (unique chặn hai request cùng key đồng thời)
                jdbc.update("INSERT INTO bank_transactions (merchant_id, merchant_reference, payment_intent_id, idempotency_key, request_hash, payer_account_no, amount, currency, status, provider_txn_id, created_at, updated_at) "
                                + "VALUES (:m, :ref, :pi, :k, :h, :p, :a, :c, 'INITIATED', :tid, :now, :now)",
                        new MapSqlParameterSource().addValue("m", merchant).addValue("ref", req.get("merchantReference")).addValue("pi", req.get("paymentIntentId"))
                                .addValue("k", key).addValue("h", hash).addValue("p", payer).addValue("a", amount).addValue("c", currency)
                                .addValue("tid", providerTxnId).addValue("now", Timestamp.from(now)));
                Long txnId = jdbc.queryForObject("SELECT id FROM bank_transactions WHERE provider_txn_id = :t", new MapSqlParameterSource("t", providerTxnId), Long.class);

                String status;
                String failure = null;
                if (account == null) { status = "FAILED"; failure = "ACCOUNT_NOT_FOUND"; }
                else if ("DECLINED".equals(scenario)) { status = "FAILED"; failure = "DECLINED"; }
                else if (!currency.equals(account.get("currency"))) { status = "FAILED"; failure = "CURRENCY_MISMATCH"; }
                else {
                    // khóa theo thứ tự id tăng dần: payer, merchant
                    Long merchantAcc = jdbc.queryForObject("SELECT id FROM bank_accounts WHERE merchant = TRUE ORDER BY id LIMIT 1", new MapSqlParameterSource(), Long.class);
                    Long payerId = ((Number) account.get("id")).longValue();
                    List<Long> ids = new ArrayList<>(List.of(payerId, merchantAcc));
                    Collections.sort(ids);
                    Map<Long, BigDecimal> bal = new HashMap<>();
                    for (Long id : ids) {
                        bal.put(id, jdbc.queryForObject("SELECT balance FROM bank_accounts WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id), BigDecimal.class));
                    }
                    if (bal.get(payerId).compareTo(amount) < 0) {
                        status = "FAILED";
                        failure = "INSUFFICIENT_FUNDS";   // không đổi sổ cái, không gửi webhook SUCCEEDED
                    } else {
                        jdbc.update("UPDATE bank_accounts SET balance = balance - :a, version = version + 1 WHERE id = :id", new MapSqlParameterSource("a", amount).addValue("id", payerId));
                        jdbc.update("UPDATE bank_accounts SET balance = balance + :a, version = version + 1 WHERE id = :id", new MapSqlParameterSource("a", amount).addValue("id", merchantAcc));
                        ledger(txnId, "PAYMENT", providerTxnId, payerId, amount, BigDecimal.ZERO, now);
                        ledger(txnId, "PAYMENT", providerTxnId, merchantAcc, BigDecimal.ZERO, amount, now);
                        status = "SUCCEEDED";
                    }
                }
                jdbc.update("UPDATE bank_transactions SET status = :s, failure_code = :f, updated_at = :now WHERE id = :id",
                        new MapSqlParameterSource("s", status).addValue("f", failure).addValue("now", Timestamp.from(now)).addValue("id", txnId));
                enqueuePaymentWebhooks(scenario, merchant, key, providerTxnId, status, failure, amount, currency, now);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("providerTxnId", providerTxnId);
                out.put("status", status);
                out.put("failureCode", failure);
                out.put("amount", amount);
                out.put("currency", currency);
                return out;
            });
        } catch (DuplicateKeyException e) {
            // hai request cùng key đồng thời: bản kia đã commit -> trả bản đó
            return replay(findTxn(merchant, key).orElseThrow(), hash);
        }
        if ("TIMEOUT_AFTER_DEBIT".equals(scenario) && "SUCCEEDED".equals(result.get("status"))) {
            // Đã commit trừ tiền nhưng phản hồi chậm hơn timeout của client -> client phải đối soát bằng idempotencyKey.
            try { Thread.sleep(timeoutDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return result;
    }

    private void enqueuePaymentWebhooks(String scenario, String merchant, String key, String txnId, String status, String failure,
                                        BigDecimal amount, String currency, Instant now) {
        if ("CALLBACK_LOST".equals(scenario)) return;                       // callback bị mất: chỉ đối soát mới phục hồi được
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eventId", "evt-" + txnId + "-" + status);
        p.put("type", "PAYMENT_RESULT");
        p.put("merchantId", merchant);
        p.put("idempotencyKey", key);
        p.put("providerTxnId", txnId);
        p.put("status", status);
        p.put("failureCode", failure);
        p.put("amount", amount);
        p.put("currency", currency);
        p.put("occurredAt", now.toString());
        Instant at = "DELAYED_CALLBACK".equals(scenario) ? now.plusSeconds(delayedCallbackSeconds) : now;
        int copies = "DUPLICATE_CALLBACK".equals(scenario) ? 3 : 1;           // callback lặp: cùng eventId giao nhiều lần
        for (int i = 0; i < copies; i++) outbox((String) p.get("eventId"), "PAYMENT_RESULT", p, at.plusSeconds(i));
    }

    private Map<String, Object> replay(Map<String, Object> t, String hash) {
        if (!hash.equals(t.get("request_hash"))) throw new BankException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Cùng idempotencyKey nhưng khác nội dung");
        return view(t);
    }

    public Optional<Map<String, Object>> lookupByKey(String merchant, String key) {
        return findTxn(merchant, key).map(this::view);
    }

    private Optional<Map<String, Object>> findTxn(String merchant, String key) {
        return jdbc.queryForList("SELECT * FROM bank_transactions WHERE merchant_id = :m AND idempotency_key = :k",
                new MapSqlParameterSource("m", merchant).addValue("k", key)).stream().findFirst();
    }

    private Map<String, Object> view(Map<String, Object> t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providerTxnId", t.get("provider_txn_id"));
        out.put("status", t.get("status"));
        out.put("failureCode", t.get("failure_code"));
        out.put("amount", t.get("amount"));
        out.put("currency", t.get("currency"));
        return out;
    }

    // ------------------------------------------------------------------ REFUND (bút toán đảo, idempotent theo refundKey)
    public Map<String, Object> refund(Map<String, Object> req) {
        String merchant = str(req, "merchantId");
        if (!merchantId.equals(merchant)) throw new BankException(HttpStatus.FORBIDDEN, "UNKNOWN_MERCHANT", "Merchant không hợp lệ");
        String refundKey = str(req, "refundKey");
        String txnProvider = str(req, "providerTxnId");
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        String hash = Crypto.sha256(merchant + "|" + txnProvider + "|" + amount.stripTrailingZeros().toPlainString());

        Optional<Map<String, Object>> existing = findRefund(merchant, refundKey);
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().get("request_hash"))) throw new BankException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Cùng refundKey nhưng khác nội dung");
            return refundView(existing.get());
        }
        try {
            return tx.execute(s -> {
                Map<String, Object> t = jdbc.queryForList("SELECT * FROM bank_transactions WHERE provider_txn_id = :t AND status = 'SUCCEEDED' FOR UPDATE",
                        new MapSqlParameterSource("t", txnProvider)).stream().findFirst()
                        .orElseThrow(() -> new BankException(HttpStatus.UNPROCESSABLE_ENTITY, "TXN_NOT_REFUNDABLE", "Giao dịch không tồn tại hoặc chưa thành công"));
                BigDecimal already = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM bank_refunds WHERE txn_id = :id AND status = 'SUCCEEDED'",
                        new MapSqlParameterSource("id", t.get("id")), BigDecimal.class);
                if (already.add(amount).compareTo((BigDecimal) t.get("amount")) > 0) {
                    throw new BankException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDED", "Tổng hoàn vượt số tiền giao dịch");
                }
                Map<String, Object> payer = jdbc.queryForMap("SELECT * FROM bank_accounts WHERE account_no = :n", new MapSqlParameterSource("n", t.get("payer_account_no")));
                String providerRef = "RF" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
                Instant now = Instant.now();
                String status = "SUCCEEDED";
                String failure = null;
                if ("REFUND_FAIL".equals(payer.get("scenario"))) { status = "FAILED"; failure = "REFUND_REJECTED"; }
                Long txnId = ((Number) t.get("id")).longValue();
                if (status.equals("SUCCEEDED")) {
                    Long merchantAcc = jdbc.queryForObject("SELECT id FROM bank_accounts WHERE merchant = TRUE ORDER BY id LIMIT 1", new MapSqlParameterSource(), Long.class);
                    Long payerId = ((Number) payer.get("id")).longValue();
                    List<Long> ids = new ArrayList<>(List.of(payerId, merchantAcc));
                    Collections.sort(ids);
                    Map<Long, BigDecimal> bal = new HashMap<>();
                    for (Long id : ids) bal.put(id, jdbc.queryForObject("SELECT balance FROM bank_accounts WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id), BigDecimal.class));
                    if (bal.get(merchantAcc).compareTo(amount) < 0) { status = "FAILED"; failure = "MERCHANT_INSUFFICIENT"; }
                    else {
                        jdbc.update("UPDATE bank_accounts SET balance = balance - :a, version = version + 1 WHERE id = :id", new MapSqlParameterSource("a", amount).addValue("id", merchantAcc));
                        jdbc.update("UPDATE bank_accounts SET balance = balance + :a, version = version + 1 WHERE id = :id", new MapSqlParameterSource("a", amount).addValue("id", payerId));
                        ledger(txnId, "REFUND", providerRef, merchantAcc, amount, BigDecimal.ZERO, now);   // bút toán ngược
                        ledger(txnId, "REFUND", providerRef, payerId, BigDecimal.ZERO, amount, now);
                    }
                }
                jdbc.update("INSERT INTO bank_refunds (merchant_id, refund_key, request_hash, txn_id, amount, status, failure_code, provider_ref, created_at) "
                                + "VALUES (:m, :k, :h, :t, :a, :s, :f, :r, :now)",
                        new MapSqlParameterSource().addValue("m", merchant).addValue("k", refundKey).addValue("h", hash).addValue("t", txnId)
                                .addValue("a", amount).addValue("s", status).addValue("f", failure).addValue("r", providerRef).addValue("now", Timestamp.from(now)));
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("eventId", "evt-" + providerRef + "-" + status);
                p.put("type", "REFUND_RESULT");
                p.put("refundKey", refundKey);
                p.put("providerRef", providerRef);
                p.put("status", status);
                p.put("failureCode", failure);
                outbox((String) p.get("eventId"), "REFUND_RESULT", p, now);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("providerRef", providerRef);
                out.put("status", status);
                out.put("failureCode", failure);
                return out;
            });
        } catch (DuplicateKeyException e) {
            return refundView(findRefund(merchant, refundKey).orElseThrow());
        }
    }

    public Optional<Map<String, Object>> lookupRefund(String merchant, String key) {
        return findRefund(merchant, key).map(this::refundView);
    }

    private Optional<Map<String, Object>> findRefund(String merchant, String key) {
        return jdbc.queryForList("SELECT * FROM bank_refunds WHERE merchant_id = :m AND refund_key = :k",
                new MapSqlParameterSource("m", merchant).addValue("k", key)).stream().findFirst();
    }

    private Map<String, Object> refundView(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providerRef", r.get("provider_ref"));
        out.put("status", r.get("status"));
        out.put("failureCode", r.get("failure_code"));
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private void ledger(Long txnId, String kind, String ref, Long accountId, BigDecimal debit, BigDecimal credit, Instant now) {
        jdbc.update("INSERT INTO bank_ledger_entries (txn_id, ref_kind, ref_id, account_id, debit, credit, created_at) VALUES (:t, :k, :r, :a, :d, :c, :now)",
                new MapSqlParameterSource().addValue("t", txnId).addValue("k", kind).addValue("r", ref).addValue("a", accountId)
                        .addValue("d", debit).addValue("c", credit).addValue("now", Timestamp.from(now)));
    }

    private void outbox(String eventId, String type, Map<String, Object> payload, Instant at) {
        try {
            jdbc.update("INSERT INTO bank_webhook_outbox (event_id, event_type, payload, next_attempt_at, created_at) VALUES (:e, :t, :p, :at, :now)",
                    new MapSqlParameterSource().addValue("e", eventId).addValue("t", type).addValue("p", mapper.writeValueAsString(payload))
                            .addValue("at", Timestamp.from(at)).addValue("now", Timestamp.from(Instant.now())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null || String.valueOf(v).isBlank()) throw new BankException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Thiếu " + k);
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ admin
    public List<Map<String, Object>> accounts() {
        return jdbc.queryForList("SELECT account_no, owner_name, balance, currency, scenario, merchant FROM bank_accounts ORDER BY id", new MapSqlParameterSource());
    }

    public void setScenario(String accountNo, String scenario) {
        Set<String> ok = Set.of("SUCCESS", "DECLINED", "TIMEOUT_AFTER_DEBIT", "CALLBACK_LOST", "DUPLICATE_CALLBACK", "DELAYED_CALLBACK", "DB_OUTAGE", "REFUND_FAIL");
        if (!ok.contains(scenario)) throw new BankException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Kịch bản hợp lệ: " + ok);
        jdbc.update("UPDATE bank_accounts SET scenario = :s WHERE account_no = :n", new MapSqlParameterSource("s", scenario).addValue("n", accountNo));
    }

    public void topUp(String accountNo, BigDecimal amount) {
        if (amount.signum() <= 0) throw new BankException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "amount > 0");
        jdbc.update("UPDATE bank_accounts SET balance = balance + :a WHERE account_no = :n AND merchant = FALSE", new MapSqlParameterSource("a", amount).addValue("n", accountNo));
    }

    /** Kiểm tra bất biến sổ cái: tổng nợ == tổng có. */
    public Map<String, Object> ledgerCheck() {
        Map<String, Object> m = jdbc.queryForMap("SELECT COALESCE(SUM(debit),0) AS debit, COALESCE(SUM(credit),0) AS credit FROM bank_ledger_entries", new MapSqlParameterSource());
        BigDecimal d = (BigDecimal) m.get("debit"), c = (BigDecimal) m.get("credit");
        return Map.of("totalDebit", d, "totalCredit", c, "balanced", d.compareTo(c) == 0);
    }
}
