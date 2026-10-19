package datphong.vn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import datphong.vn.domain.HmacSigner;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.security.AuthUser;
import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * UC-21..26. Nguyên tắc: mỗi metric có GRAIN và trục thời gian riêng; KHÔNG join các bảng khác grain rồi SUM.
 *  - booking_count = COUNT(DISTINCT booking), room_nights = số hàng booking_nights, tiền thu = payments, tiền hoàn = refunds:
 *    mỗi nhóm được aggregate RIÊNG rồi ghép theo dimension (tránh nhân bản booking_nights x payments).
 *  - tiền thực thu = captured(SUCCEEDED, theo captured_at) - refunded(SUCCEEDED, theo succeeded_at); refund pending KHÔNG trừ.
 *  - tỷ lệ = tổng tử số / tổng mẫu số (không trung bình phần trăm từng property); mẫu số 0 -> null.
 *  - phạm vi dữ liệu được ÉP trong SQL (owner_id) và là một phần của khóa cache (scopeHash) -> không lộ cache chéo tenant.
 * Cache Redis chỉ tăng tốc, luôn trả asOf / sourceType; PostgreSQL là nguồn tính đúng.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CacheService cache;
    private final ConfigService config;
    private final Clock clock;

    public record Scope(Long ownerId, boolean admin) {}

    private Scope scope(AuthUser u) {
        return new Scope(u.getId(), u.hasRole("ADMIN"));
    }

    /** Fingerprint phạm vi: đổi quyền/phạm vi dữ liệu -> khóa cache đổi theo. */
    private String scopeHash(AuthUser u) {
        return HmacSigner.sha256Hex((u.hasRole("ADMIN") ? "ADMIN" : "HOST:" + u.getId()) + "|" + String.join(",", new TreeSet<>(u.getPermissions()))).substring(0, 12);
    }

    private void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from)) throw BusinessException.validation("Khoảng ngày không hợp lệ (to > from, [from, to))");
        if (ChronoUnit.DAYS.between(from, to) > config.getInt("analytics.maxRangeDays")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Khoảng ngày vượt giới hạn " + config.getInt("analytics.maxRangeDays") + " ngày; hãy dùng export bất đồng bộ");
        }
    }

    /** Điều kiện scope + property dưới dạng SQL, luôn có owner filter cho HOST. */
    private String scopeSql(Scope s, MapSqlParameterSource ps, Long propertyId, String propAlias) {
        StringBuilder sb = new StringBuilder();
        if (!s.admin()) {
            sb.append(" AND ").append(propAlias).append(".owner_id = :owner");
            ps.addValue("owner", s.ownerId());
        }
        if (propertyId != null) {
            sb.append(" AND ").append(propAlias).append(".id = :pid");
            ps.addValue("pid", propertyId);
        }
        return sb.toString();
    }

    private <T> T cached(String key, Class<?> unused, java.util.function.Supplier<Map<String, Object>> loader) {
        Optional<Map<String, Object>> hit = cache.get(key, new TypeReference<Map<String, Object>>() {});
        if (hit.isPresent()) {
            Map<String, Object> m = new LinkedHashMap<>(hit.get());
            m.put("sourceType", "CACHE");
            @SuppressWarnings("unchecked") T t = (T) m;
            return t;
        }
        // single-flight ngắn chống stampede: nếu có request khác đang tính, chờ ngắn rồi đọc lại cache
        if (!cache.tryLease(key + ":lease", Duration.ofSeconds(10))) {
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Optional<Map<String, Object>> again = cache.get(key, new TypeReference<Map<String, Object>>() {});
            if (again.isPresent()) {
                Map<String, Object> m = new LinkedHashMap<>(again.get());
                m.put("sourceType", "CACHE");
                @SuppressWarnings("unchecked") T t = (T) m;
                return t;
            }
        }
        Map<String, Object> fresh = loader.get();
        cache.set(key, fresh, Duration.ofSeconds(config.getInt("analytics.cache.ttlSeconds")));
        cache.delete(key + ":lease");
        @SuppressWarnings("unchecked") T t = (T) fresh;
        return t;
    }

    // ------------------------------------------------------------------ UC-21 overview
    @Transactional(readOnly = true)
    public Map<String, Object> overview(AuthUser u, LocalDate from, LocalDate to, Long propertyId, boolean comparePrevious) {
        checkRange(from, to);
        String key = "an:v3:" + scopeHash(u) + ":ov:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId + "|" + comparePrevious).substring(0, 16);
        return cached(key, Map.class, () -> {
            Scope s = scope(u);
            Map<String, Object> cur = overviewPeriod(s, from, to, propertyId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", Map.of("from", from.toString(), "to", to.toString(), "semantics", "[from,to) - booking theo created_at, tiền theo captured_at/refund succeeded_at, đêm theo stay_date"));
            out.put("metrics", cur);
            if (comparePrevious) {
                long days = ChronoUnit.DAYS.between(from, to);   // kỳ trước cùng độ dài (cùng số ngày), nêu rõ quy ước
                Map<String, Object> prev = overviewPeriod(s, from.minusDays(days), from, propertyId);
                out.put("previous", prev);
                out.put("deltaNetCash", ((BigDecimal) cur.get("netCash")).subtract((BigDecimal) prev.get("netCash")));
                out.put("comparisonRule", "kỳ trước = " + days + " ngày liền trước, cùng quy ước thời gian");
            }
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            out.put("dataFreshness", "LIVE_SQL");
            return out;
        });
    }

    private Map<String, Object> overviewPeriod(Scope s, LocalDate from, LocalDate to, Long propertyId) {
        Instant f = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant t = to.atStartOfDay(ZoneOffset.UTC).toInstant();
        // 1) booking: grain = 1 dòng / booking
        MapSqlParameterSource p1 = new MapSqlParameterSource("f", java.sql.Timestamp.from(f)).addValue("t", java.sql.Timestamp.from(t));
        String sc1 = scopeSql(s, p1, propertyId, "p");
        Map<String, Object> b = jdbc.queryForMap("SELECT COUNT(*) AS created, "
                + "COUNT(*) FILTER (WHERE b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')) AS confirmed, "
                + "COUNT(*) FILTER (WHERE b.status IN ('CANCELLED','REFUND_PENDING')) AS cancelled, "
                + "COALESCE(SUM(b.total_amount),0) AS gross "
                + "FROM bookings b JOIN properties p ON p.id = b.property_id WHERE b.created_at >= :f AND b.created_at < :t" + sc1, p1);
        // 2) đêm bán: grain = 1 dòng / đêm, trục stay_date, loại booking không chiếm phòng
        MapSqlParameterSource p2 = new MapSqlParameterSource("from", from).addValue("to", to);
        String sc2 = scopeSql(s, p2, propertyId, "p");
        Long nights = jdbc.queryForObject("SELECT COUNT(*) FROM booking_nights n JOIN bookings b ON b.id = n.booking_id JOIN properties p ON p.id = b.property_id "
                + "WHERE n.stay_date >= :from AND n.stay_date < :to AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')" + sc2, p2, Long.class);
        // 3) tiền đã thu: grain = 1 dòng / payment SUCCEEDED, trục captured_at
        MapSqlParameterSource p3 = new MapSqlParameterSource("f", java.sql.Timestamp.from(f)).addValue("t", java.sql.Timestamp.from(t));
        String sc3 = scopeSql(s, p3, propertyId, "p");
        Map<String, Object> cap = jdbc.queryForMap("SELECT COALESCE(SUM(pay.amount),0) AS captured, COUNT(*) AS cnt FROM payments pay JOIN bookings b ON b.id = pay.booking_id "
                + "JOIN properties p ON p.id = b.property_id WHERE pay.status = 'SUCCEEDED' AND pay.captured_at >= :f AND pay.captured_at < :t" + sc3, p3);
        // 4) tiền đã hoàn: grain = 1 dòng / refund SUCCEEDED, trục succeeded_at (pending KHÔNG tính)
        MapSqlParameterSource p4 = new MapSqlParameterSource("f", java.sql.Timestamp.from(f)).addValue("t", java.sql.Timestamp.from(t));
        String sc4 = scopeSql(s, p4, propertyId, "p");
        BigDecimal refunded = jdbc.queryForObject("SELECT COALESCE(SUM(r.amount),0) FROM refunds r JOIN bookings b ON b.id = r.booking_id JOIN properties p ON p.id = b.property_id "
                + "WHERE r.status = 'SUCCEEDED' AND r.succeeded_at >= :f AND r.succeeded_at < :t" + sc4, p4, BigDecimal.class);
        MapSqlParameterSource p5 = new MapSqlParameterSource();
        String sc5 = scopeSql(s, p5, propertyId, "p");
        BigDecimal pendingRefund = jdbc.queryForObject("SELECT COALESCE(SUM(r.amount),0) FROM refunds r JOIN bookings b ON b.id = r.booking_id JOIN properties p ON p.id = b.property_id "
                + "WHERE r.status IN ('REQUESTED','PROCESSING','FAILED')" + sc5, p5, BigDecimal.class);
        MapSqlParameterSource p6 = new MapSqlParameterSource();
        String sc6 = scopeSql(s, p6, propertyId, "p");
        Long sellable = jdbc.queryForObject("SELECT COUNT(*) FROM rooms r JOIN properties p ON p.id = r.property_id WHERE r.status = 'ACTIVE' AND p.status = 'PUBLISHED'" + sc6, p6, Long.class);

        BigDecimal captured = (BigDecimal) cap.get("captured");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingsCreated", ((Number) b.get("created")).longValue());
        m.put("bookingsConfirmed", ((Number) b.get("confirmed")).longValue());
        m.put("bookingsCancelled", ((Number) b.get("cancelled")).longValue());
        m.put("grossBookingValue", b.get("gross"));
        m.put("roomNightsSold", nights == null ? 0 : nights);
        m.put("capturedAmount", captured);
        m.put("capturedPayments", ((Number) cap.get("cnt")).longValue());
        m.put("refundedAmount", refunded);
        m.put("pendingRefundAmount", pendingRefund);                   // dự kiến hoàn, KHÔNG trừ vào net
        m.put("netCash", captured.subtract(refunded));                  // net cash = captured - refunded (succeeded)
        m.put("sellableRooms", sellable == null ? 0 : sellable);
        m.put("currency", "VND");
        return m;
    }

    // ------------------------------------------------------------------ UC-22 query đa chiều (whitelist)
    private static final Map<String, String> DIM_SQL = Map.of(
            "property", "p.id", "roomType", "b.room_type_id", "status", "b.status",
            "createdDate", "(b.created_at AT TIME ZONE p.timezone)::date", "checkInDate", "b.check_in", "checkOutDate", "b.check_out",
            "source", "b.source_channel");
    private static final Set<String> METRICS = Set.of("bookings", "roomNights", "gross");
    private static final Map<String, String> TIME_AXIS = Map.of("createdAt", "(b.created_at AT TIME ZONE p.timezone)::date", "checkIn", "b.check_in", "checkOut", "b.check_out");

    public record QueryRequest(List<String> metrics, List<String> groupBy, String timeAxis, LocalDate from, LocalDate to, Long propertyId,
                               List<String> statuses, BigDecimal minTotal, BigDecimal maxTotal, Integer minNights, Integer limit) {}

    @Transactional(readOnly = true)
    public Map<String, Object> query(AuthUser u, QueryRequest r) {
        checkRange(r.from(), r.to());
        List<String> metrics = r.metrics() == null || r.metrics().isEmpty() ? List.of("bookings") : r.metrics();
        List<String> groups = r.groupBy() == null ? List.of() : r.groupBy();
        if (groups.size() > 3) throw BusinessException.validation("groupBy tối đa 3 chiều");
        if (!METRICS.containsAll(metrics)) throw BusinessException.validation("metric không nằm trong whitelist " + METRICS);
        for (String g : groups) if (!DIM_SQL.containsKey(g)) throw BusinessException.validation("dimension không hợp lệ: " + g + " (cho phép " + DIM_SQL.keySet() + ")");
        String axisName = r.timeAxis() == null ? "checkIn" : r.timeAxis();
        String axis = TIME_AXIS.get(axisName);
        if (axis == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "timeAxis bắt buộc là một trong " + TIME_AXIS.keySet());
        int limit = Math.min(r.limit() == null ? 200 : r.limit(), 1000);
        String key = "an:v3:" + scopeHash(u) + ":q:" + HmacSigner.sha256Hex(Json.write(r)).substring(0, 20);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("from", r.from()).addValue("to", r.to());
            String sc = scopeSql(scope(u), ps, r.propertyId(), "p");
            StringBuilder where = new StringBuilder(" WHERE " + axis + " >= :from AND " + axis + " < :to" + sc);
            if (r.statuses() != null && !r.statuses().isEmpty()) { where.append(" AND b.status IN (:statuses)"); ps.addValue("statuses", r.statuses()); }
            if (r.minTotal() != null) { where.append(" AND b.total_amount >= :minTotal"); ps.addValue("minTotal", r.minTotal()); }
            if (r.maxTotal() != null) { where.append(" AND b.total_amount <= :maxTotal"); ps.addValue("maxTotal", r.maxTotal()); }
            if (r.minNights() != null) { where.append(" AND (b.check_out - b.check_in) >= :minNights"); ps.addValue("minNights", r.minNights()); }

            // booking_nights KHÔNG join vào truy vấn booking: nights = (check_out - check_in), mỗi booking đúng một dòng
            // => COUNT(DISTINCT booking) và tổng room-nights luôn khớp (1 booking 3 đêm = 1 booking + 3 room-nights).
            StringBuilder sel = new StringBuilder();
            for (String g : groups) sel.append(DIM_SQL.get(g)).append(" AS ").append(g).append(", ");
            sel.append("COUNT(DISTINCT b.id) AS bookings, COALESCE(SUM(b.check_out - b.check_in),0) AS room_nights, COALESCE(SUM(b.total_amount),0) AS gross");
            String groupBy = groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups.stream().map(DIM_SQL::get).toList()) + " ORDER BY " + String.join(", ", groups.stream().map(DIM_SQL::get).toList());
            ps.addValue("limit", limit);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + sel + " FROM bookings b JOIN properties p ON p.id = b.property_id" + where + groupBy + " LIMIT :limit", ps);
            Map<String, Object> totals = jdbc.queryForMap("SELECT COUNT(DISTINCT b.id) AS bookings, COALESCE(SUM(b.check_out - b.check_in),0) AS room_nights, COALESCE(SUM(b.total_amount),0) AS gross "
                    + "FROM bookings b JOIN properties p ON p.id = b.property_id" + where, ps);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("timeAxis", axisName);
            out.put("rows", rows);
            out.put("totals", totals);
            out.put("truncated", rows.size() >= limit);
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }

    // ------------------------------------------------------------------ UC-24 công suất
    @Transactional(readOnly = true)
    public Map<String, Object> occupancy(AuthUser u, LocalDate from, LocalDate to, Long propertyId) {
        checkRange(from, to);
        String key = "an:v3:" + scopeHash(u) + ":occ:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId).substring(0, 16);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("from", from).addValue("to", to);
            String sc = scopeSql(scope(u), ps, propertyId, "p");
            // date spine (generate_series) x phòng ACTIVE = room-nights bán được; anti-join với allocation đã chiếm (CONFIRMED/CHECKED_IN/COMPLETED)
            List<Map<String, Object>> days = jdbc.queryForList(
                    "SELECT d::date AS day, COUNT(r.id) AS sellable, "
                            + "COUNT(r.id) FILTER (WHERE EXISTS (SELECT 1 FROM bookings b WHERE b.room_id = r.id AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED') "
                            + "AND b.check_in <= d::date AND b.check_out > d::date)) AS occupied, "
                            + "COALESCE(SUM(b2.rate),0) AS room_revenue "
                            + "FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') d "
                            + "CROSS JOIN rooms r JOIN properties p ON p.id = r.property_id "
                            + "LEFT JOIN LATERAL (SELECT n.nightly_rate AS rate FROM booking_nights n JOIN bookings bb ON bb.id = n.booking_id "
                            + "WHERE bb.room_id = r.id AND n.stay_date = d::date AND bb.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')) b2 ON TRUE "
                            + "WHERE r.status = 'ACTIVE' AND p.status IN ('PUBLISHED','PAUSED')" + sc + " GROUP BY d ORDER BY d", ps);
            long sellable = 0, occupied = 0;
            BigDecimal revenue = BigDecimal.ZERO;
            List<Map<String, Object>> heat = new ArrayList<>();
            for (Map<String, Object> d : days) {
                long s = ((Number) d.get("sellable")).longValue(), o = ((Number) d.get("occupied")).longValue();
                BigDecimal rev = (BigDecimal) d.get("room_revenue");
                sellable += s; occupied += o; revenue = revenue.add(rev);
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("day", d.get("day").toString());
                h.put("sellableRoomNights", s);
                h.put("occupiedRoomNights", o);
                h.put("occupancy", s == 0 ? null : BigDecimal.valueOf(o).divide(BigDecimal.valueOf(s), 4, java.math.RoundingMode.HALF_UP)); // mẫu số 0 -> null, không hiển thị 0% sai nghĩa
                heat.add(h);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", heat);
            out.put("occupiedRoomNights", occupied);
            out.put("sellableRoomNights", sellable);
            // tổng hợp = tổng tử / tổng mẫu (không trung bình % từng ngày/property)
            out.put("occupancy", sellable == 0 ? null : BigDecimal.valueOf(occupied).divide(BigDecimal.valueOf(sellable), 4, java.math.RoundingMode.HALF_UP));
            out.put("adr", occupied == 0 ? null : revenue.divide(BigDecimal.valueOf(occupied), 0, java.math.RoundingMode.HALF_UP));
            out.put("revPar", sellable == 0 ? null : revenue.divide(BigDecimal.valueOf(sellable), 0, java.math.RoundingMode.HALF_UP));
            out.put("roomRevenueExcludingTax", revenue);
            out.put("note", "ADR/RevPAR dựa trên giá phòng từng đêm (snapshot booking_nights), chưa gồm thuế/phụ phí; held (PENDING_PAYMENT) không tính là occupied");
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }

    // ------------------------------------------------------------------ UC-25 funnel + hủy + lỗi thanh toán
    @Transactional(readOnly = true)
    public Map<String, Object> funnel(AuthUser u, LocalDate from, LocalDate to, Long propertyId) {
        checkRange(from, to);
        String key = "an:v3:" + scopeHash(u) + ":fn:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId).substring(0, 16);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("f", java.sql.Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()))
                    .addValue("t", java.sql.Timestamp.from(to.atStartOfDay(ZoneOffset.UTC).toInstant()));
            String sc = scopeSql(scope(u), ps, propertyId, "p");
            // mỗi (bước, ref/session) chỉ đếm MỘT lần dù event bị gửi lại; không chứa PII
            List<Map<String, Object>> steps = jdbc.queryForList("SELECT e.event_type AS step, COUNT(DISTINCT COALESCE(e.ref_id, e.session_key, e.event_id)) AS n "
                    + "FROM business_events e JOIN properties p ON p.id = e.property_id WHERE e.event_time >= :f AND e.event_time < :t" + sc + " GROUP BY e.event_type", ps);
            Map<String, Long> by = new HashMap<>();
            steps.forEach(m -> by.put((String) m.get("step"), ((Number) m.get("n")).longValue()));
            List<String> order = List.of("QUOTE_ISSUED", "HOLD_CREATED", "PAYMENT_INITIATED", "PAYMENT_CAPTURED", "BOOKING_CONFIRMED", "CHECKIN_COMPLETED");
            List<Map<String, Object>> funnel = new ArrayList<>();
            Long prev = null;
            long first = by.getOrDefault(order.get(0), 0L);
            for (String st : order) {
                long n = by.getOrDefault(st, 0L);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("step", st);
                m.put("count", n);
                m.put("conversionFromPrevious", prev == null || prev == 0 ? null : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(prev), 4, java.math.RoundingMode.HALF_UP));
                m.put("conversionFromFirst", first == 0 ? null : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(first), 4, java.math.RoundingMode.HALF_UP));
                funnel.add(m);
                prev = n;
            }
            List<Map<String, Object>> failures = jdbc.queryForList("SELECT COALESCE(e.code,'UNKNOWN') AS code, COUNT(DISTINCT e.event_id) AS n FROM business_events e JOIN properties p ON p.id = e.property_id "
                    + "WHERE e.event_type = 'PAYMENT_FAILED' AND e.event_time >= :f AND e.event_time < :t" + sc + " GROUP BY e.code ORDER BY n DESC", ps);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("funnel", funnel);
            out.put("paymentFailuresByCode", failures);
            out.put("coverageNote", "Funnel chỉ dựa trên event log (business_events); nếu event bị mất thì tỷ lệ chỉ phản ánh phần đã ghi nhận. Mẫu nhỏ -> tránh kết luận.");
            out.put("firstStepSample", first);
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }
}
