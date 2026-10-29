package datphong.vn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import datphong.vn.domain.HmacSigner;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.config.AppProperties;
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
    private final AppProperties app;
    private final Clock clock;

    public record Scope(Long ownerId, boolean admin) {}

    private Scope scope(AuthUser u) {
        return new Scope(u.getId(), u.hasRole("ADMIN"));
    }

    private static final String CACHE_VERSION_KEY = "an:ver";

    /** Khóa cache = version cache analytics (UC-26 invalidation) + fingerprint phạm vi quyền (không dùng chung cache giữa HOST/ADMIN). */
    private String keyPrefix(AuthUser u) {
        return "an:v5:g" + cache.getLong(CACHE_VERSION_KEY) + ":" + scopeHash(u);
    }

    /**
     * UC-26: làm mới toàn bộ cache thống kê bằng cách tăng version (không quét/xóa key). Khóa cũ tự hết hạn theo TTL.
     * @return version mới; Redis lỗi -> TEMPORARILY_UNAVAILABLE (dữ liệu vẫn đúng vì PostgreSQL là nguồn tính).
     */
    public long invalidateCache() {
        long v = cache.incr(CACHE_VERSION_KEY, null);
        if (v < 0) throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "Không làm mới được cache (Redis lỗi)");
        return v;
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

    /**
     * Truy vấn trực tiếp một property: HOST phải là chủ sở hữu -> nếu không, FORBIDDEN_SCOPE (UC-18/21/22 luồng ngoại lệ),
     * thay vì trả về số liệu 0 gây hiểu nhầm. Trả về múi giờ của property để chuẩn hóa kỳ báo cáo.
     */
    private ZoneId resolveZone(Scope s, Long propertyId) {
        if (propertyId == null) return ZoneId.of(app.getDefaultTimezone());
        MapSqlParameterSource ps = new MapSqlParameterSource("pid", propertyId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT owner_id, timezone FROM properties WHERE id = :pid", ps);
        if (rows.isEmpty()) {
            if (s.admin()) throw BusinessException.notFound("Cơ sở");
            throw BusinessException.forbidden("Cơ sở nằm ngoài phạm vi dữ liệu của bạn");
        }
        Map<String, Object> r = rows.get(0);
        if (!s.admin() && !s.ownerId().equals(((Number) r.get("owner_id")).longValue())) {
            throw BusinessException.forbidden("Cơ sở nằm ngoài phạm vi dữ liệu của bạn");
        }
        return ZoneId.of((String) r.get("timezone"));
    }

    private static java.sql.Timestamp startOf(LocalDate d, ZoneId zone) {
        return java.sql.Timestamp.from(d.atStartOfDay(zone).toInstant());
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
        Scope sc = scope(u);
        ZoneId zone = resolveZone(sc, propertyId);
        String key = keyPrefix(u) + ":ov:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId + "|" + comparePrevious + "|" + zone).substring(0, 16);
        return cached(key, Map.class, () -> {
            Scope s = sc;
            Map<String, Object> cur = overviewPeriod(s, from, to, propertyId, zone);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", Map.of("from", from.toString(), "to", to.toString(), "timezone", zone.getId(),
                    "semantics", "[from,to) theo múi giờ " + zone.getId() + " - booking theo created_at, tiền theo captured_at/refund succeeded_at, đêm theo stay_date"));
            out.put("metrics", cur);
            if (comparePrevious) {
                long days = ChronoUnit.DAYS.between(from, to);   // kỳ trước cùng độ dài (cùng số ngày), nêu rõ quy ước
                Map<String, Object> prev = overviewPeriod(s, from.minusDays(days), from, propertyId, zone);
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

    private Map<String, Object> overviewPeriod(Scope s, LocalDate from, LocalDate to, Long propertyId, ZoneId zone) {
        // ranh giới ngày theo múi giờ cơ sở (hoặc múi giờ mặc định của hệ thống khi gộp nhiều cơ sở), không theo UTC
        Instant f = from.atStartOfDay(zone).toInstant();
        Instant t = to.atStartOfDay(zone).toInstant();
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

    /** UC-22: bộ lọc trạng thái, property, roomType, số tiền, số đêm, số khách, nguồn đặt; comparePrevious = so với kỳ liền trước cùng độ dài. */
    public record QueryRequest(List<String> metrics, List<String> groupBy, String timeAxis, LocalDate from, LocalDate to, Long propertyId,
                               Long roomTypeId, List<String> statuses, BigDecimal minTotal, BigDecimal maxTotal,
                               Integer minNights, Integer maxNights, Integer minGuests, Integer maxGuests, List<String> sources,
                               Boolean comparePrevious, Integer limit) {}

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
        if (r.limit() != null && (r.limit() < 1 || r.limit() > 1000)) throw BusinessException.validation("limit phải trong khoảng 1..1000");
        int limit = r.limit() == null ? 200 : r.limit();
        if (r.minNights() != null && r.maxNights() != null && r.minNights() > r.maxNights()) throw BusinessException.validation("minNights > maxNights");
        if (r.minGuests() != null && r.maxGuests() != null && r.minGuests() > r.maxGuests()) throw BusinessException.validation("minGuests > maxGuests");
        if (r.minTotal() != null && r.maxTotal() != null && r.minTotal().compareTo(r.maxTotal()) > 0) throw BusinessException.validation("minTotal > maxTotal");
        if (r.sources() != null && (r.sources().size() > 10 || !r.sources().stream().allMatch(x -> x != null && x.matches("[A-Z_]{1,30}")))) {
            throw BusinessException.validation("source không hợp lệ (mã nguồn đặt dạng chữ hoa, tối đa 10 giá trị)");
        }
        if (r.statuses() != null) {
            for (String st : r.statuses()) {
                try { datphong.vn.constant.BookingStatus.valueOf(st); } catch (Exception e) { throw BusinessException.validation("status không hợp lệ: " + st); }
            }
        }
        resolveZone(scope(u), r.propertyId());   // HOST hỏi property ngoài phạm vi -> FORBIDDEN_SCOPE
        String key = keyPrefix(u) + ":q:" + HmacSigner.sha256Hex(Json.write(r)).substring(0, 20);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("from", r.from()).addValue("to", r.to());
            String where = queryWhere(u, r, axis, ps);

            // booking_nights KHÔNG join vào truy vấn booking: nights = (check_out - check_in), mỗi booking đúng một dòng
            // => COUNT(DISTINCT booking) và tổng room-nights luôn khớp (1 booking 3 đêm = 1 booking + 3 room-nights).
            StringBuilder sel = new StringBuilder();
            for (String g : groups) sel.append(DIM_SQL.get(g)).append(" AS ").append(g).append(", ");
            sel.append(TOTALS_SQL);
            String groupBy = groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups.stream().map(DIM_SQL::get).toList()) + " ORDER BY " + String.join(", ", groups.stream().map(DIM_SQL::get).toList());
            ps.addValue("limit", limit);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + sel + " FROM bookings b JOIN properties p ON p.id = b.property_id" + where + groupBy + " LIMIT :limit", ps);
            Map<String, Object> totals = jdbc.queryForMap("SELECT " + TOTALS_SQL + " FROM bookings b JOIN properties p ON p.id = b.property_id" + where, ps);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("timeAxis", axisName);
            out.put("semantics", Map.of(
                    "period", "[from,to) trên trục " + axisName + (axisName.equals("createdAt") ? " (ngày theo múi giờ cơ sở)" : " (ngày lưu trú)"),
                    "bookings", "COUNT(DISTINCT booking)",
                    "roomNights", "SUM(check_out - check_in), ngày trả phòng không tính là đêm",
                    "gross", "SUM(total_amount) - giá trị booking, KHÔNG phải tiền thực thu"));
            out.put("rows", rows);
            out.put("totals", totals);
            if (Boolean.TRUE.equals(r.comparePrevious())) {
                // UC-22 bước 6: kỳ liền trước, cùng độ dài, cùng trục thời gian, cùng bộ lọc và phạm vi dữ liệu
                long days = ChronoUnit.DAYS.between(r.from(), r.to());
                LocalDate pFrom = r.from().minusDays(days);
                MapSqlParameterSource pps = new MapSqlParameterSource("from", pFrom).addValue("to", r.from());
                Map<String, Object> prev = jdbc.queryForMap("SELECT " + TOTALS_SQL + " FROM bookings b JOIN properties p ON p.id = b.property_id"
                        + queryWhere(u, r, axis, pps), pps);
                Map<String, Object> previous = new LinkedHashMap<>(prev);
                previous.put("from", pFrom.toString());
                previous.put("to", r.from().toString());
                out.put("previous", previous);
                Map<String, Object> change = new LinkedHashMap<>();   // Map.of không nhận null (kỳ trước = 0)
                change.put("bookings", pctChange(totals.get("bookings"), prev.get("bookings")));
                change.put("roomNights", pctChange(totals.get("room_nights"), prev.get("room_nights")));
                change.put("gross", pctChange(totals.get("gross"), prev.get("gross")));
                out.put("changePercent", change);
            }
            out.put("truncated", rows.size() >= limit);
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }

    private static final String TOTALS_SQL = "COUNT(DISTINCT b.id) AS bookings, COALESCE(SUM(b.check_out - b.check_in),0) AS room_nights, COALESCE(SUM(b.total_amount),0) AS gross";

    /** WHERE dùng chung cho kỳ chính và kỳ so sánh; chỉ bind parameter, tên cột lấy từ whitelist. */
    private String queryWhere(AuthUser u, QueryRequest r, String axis, MapSqlParameterSource ps) {
        StringBuilder where = new StringBuilder(" WHERE " + axis + " >= :from AND " + axis + " < :to" + scopeSql(scope(u), ps, r.propertyId(), "p"));
        if (r.roomTypeId() != null) { where.append(" AND b.room_type_id = :roomTypeId"); ps.addValue("roomTypeId", r.roomTypeId()); }
        if (r.statuses() != null && !r.statuses().isEmpty()) { where.append(" AND b.status IN (:statuses)"); ps.addValue("statuses", r.statuses()); }
        if (r.minTotal() != null) { where.append(" AND b.total_amount >= :minTotal"); ps.addValue("minTotal", r.minTotal()); }
        if (r.maxTotal() != null) { where.append(" AND b.total_amount <= :maxTotal"); ps.addValue("maxTotal", r.maxTotal()); }
        if (r.minNights() != null) { where.append(" AND (b.check_out - b.check_in) >= :minNights"); ps.addValue("minNights", r.minNights()); }
        if (r.maxNights() != null) { where.append(" AND (b.check_out - b.check_in) <= :maxNights"); ps.addValue("maxNights", r.maxNights()); }
        if (r.minGuests() != null) { where.append(" AND b.guest_count >= :minGuests"); ps.addValue("minGuests", r.minGuests()); }
        if (r.maxGuests() != null) { where.append(" AND b.guest_count <= :maxGuests"); ps.addValue("maxGuests", r.maxGuests()); }
        if (r.sources() != null && !r.sources().isEmpty()) { where.append(" AND b.source_channel IN (:sources)"); ps.addValue("sources", r.sources()); }
        return where.toString();
    }

    /** % thay đổi so với kỳ trước; kỳ trước = 0 -> null (không chia cho 0, không tự suy diễn). */
    private static BigDecimal pctChange(Object current, Object previous) {
        BigDecimal cur = new BigDecimal(String.valueOf(current));
        BigDecimal prev = new BigDecimal(String.valueOf(previous));
        if (prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 1, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ UC-24 công suất
    @Transactional(readOnly = true)
    public Map<String, Object> occupancy(AuthUser u, LocalDate from, LocalDate to, Long propertyId) {
        checkRange(from, to);
        resolveZone(scope(u), propertyId);
        String key = keyPrefix(u) + ":occ:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId).substring(0, 16);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("from", from).addValue("to", to);
            String sc = scopeSql(scope(u), ps, propertyId, "p");
            // date spine (generate_series) x phòng ACTIVE = room-nights bán được; anti-join với allocation đã chiếm (CONFIRMED/CHECKED_IN/COMPLETED)
            List<Map<String, Object>> days = jdbc.queryForList(
                    "SELECT d::date AS day, COUNT(r.id) AS sellable, "
                            + "COUNT(r.id) FILTER (WHERE EXISTS (SELECT 1 FROM bookings b WHERE b.room_id = r.id AND b.status IN ('CONFIRMED','CHECKED_IN','COMPLETED') "
                            + "AND b.check_in <= d::date AND b.check_out > d::date)) AS occupied, "
                            + "COUNT(r.id) FILTER (WHERE EXISTS (SELECT 1 FROM bookings b WHERE b.room_id = r.id AND b.status = 'PENDING_PAYMENT' "
                            + "AND b.expires_at > now() AND b.check_in <= d::date AND b.check_out > d::date)) AS held, "
                            + "COUNT(DISTINCT r.room_type_id) FILTER (WHERE NOT EXISTS (SELECT 1 FROM room_rates rr WHERE rr.room_type_id = r.room_type_id "
                            + "AND rr.stay_date = d::date)) AS types_missing_rate, "
                            + "COALESCE(SUM(b2.rate),0) AS room_revenue "
                            + "FROM generate_series(CAST(:from AS date), CAST(:to AS date) - 1, interval '1 day') d "
                            + "CROSS JOIN rooms r JOIN properties p ON p.id = r.property_id "
                            + "LEFT JOIN LATERAL (SELECT n.nightly_rate AS rate FROM booking_nights n JOIN bookings bb ON bb.id = n.booking_id "
                            + "WHERE bb.room_id = r.id AND n.stay_date = d::date AND bb.status IN ('CONFIRMED','CHECKED_IN','COMPLETED')) b2 ON TRUE "
                            + "WHERE r.status = 'ACTIVE' AND p.status IN ('PUBLISHED','PAUSED')" + sc + " GROUP BY d ORDER BY d", ps);
            long sellable = 0, occupied = 0, held = 0;
            List<String> missingRateDays = new ArrayList<>();
            BigDecimal revenue = BigDecimal.ZERO;
            List<Map<String, Object>> heat = new ArrayList<>();
            for (Map<String, Object> d : days) {
                long s = ((Number) d.get("sellable")).longValue(), o = ((Number) d.get("occupied")).longValue();
                BigDecimal rev = (BigDecimal) d.get("room_revenue");
                long hd = ((Number) d.get("held")).longValue();
                long miss = ((Number) d.get("types_missing_rate")).longValue();
                sellable += s; occupied += o; held += hd; revenue = revenue.add(rev);
                if (miss > 0) missingRateDays.add(d.get("day").toString());
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("day", d.get("day").toString());
                h.put("sellableRoomNights", s);
                h.put("occupiedRoomNights", o);
                h.put("heldRoomNights", hd);                 // hold còn hạn: chiếm tồn kho nhưng chưa phải đêm bán
                h.put("roomTypesMissingRate", miss);         // thiếu giá: đánh dấu, không tự suy diễn
                h.put("occupancy", s == 0 ? null : BigDecimal.valueOf(o).divide(BigDecimal.valueOf(s), 4, java.math.RoundingMode.HALF_UP)); // mẫu số 0 -> null, không hiển thị 0% sai nghĩa
                heat.add(h);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("days", heat);
            out.put("occupiedRoomNights", occupied);
            out.put("sellableRoomNights", sellable);
            out.put("heldRoomNights", held);
            out.put("daysMissingRate", missingRateDays);
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
        ZoneId zone = resolveZone(scope(u), propertyId);
        String key = keyPrefix(u) + ":fn:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId + "|" + zone).substring(0, 16);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("f", startOf(from, zone)).addValue("t", startOf(to, zone));
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
            // hủy: số booking tạo trong kỳ bị hủy / tổng booking tạo trong kỳ (cùng phạm vi, cùng grain booking)
            Map<String, Object> cancel = jdbc.queryForMap("SELECT COUNT(*) AS created, COUNT(*) FILTER (WHERE b.status IN ('CANCELLED','REFUND_PENDING')) AS cancelled, "
                    + "COUNT(*) FILTER (WHERE b.status IN ('EXPIRED','NEEDS_ATTENTION')) AS expired "
                    + "FROM bookings b JOIN properties p ON p.id = b.property_id WHERE b.created_at >= :f AND b.created_at < :t" + sc, ps);
            long createdB = ((Number) cancel.get("created")).longValue();
            long cancelledB = ((Number) cancel.get("cancelled")).longValue();
            Map<String, Object> cancellation = new LinkedHashMap<>();
            cancellation.put("bookingsCreated", createdB);
            cancellation.put("bookingsCancelled", cancelledB);
            cancellation.put("bookingsExpired", ((Number) cancel.get("expired")).longValue());
            cancellation.put("cancellationRate", createdB == 0 ? null : BigDecimal.valueOf(cancelledB).divide(BigDecimal.valueOf(createdB), 4, java.math.RoundingMode.HALF_UP));
            // payment attempt là đại lượng KHÁC booking: đếm riêng theo trạng thái payment (grain = payment)
            List<Map<String, Object>> attempts = jdbc.queryForList("SELECT pay.status AS status, COUNT(*) AS n FROM payments pay JOIN bookings b ON b.id = pay.booking_id "
                    + "JOIN properties p ON p.id = b.property_id WHERE pay.created_at >= :f AND pay.created_at < :t" + sc + " GROUP BY pay.status ORDER BY pay.status", ps);
            List<Map<String, Object>> failures = jdbc.queryForList("SELECT COALESCE(e.code,'UNKNOWN') AS code, COUNT(DISTINCT e.event_id) AS n FROM business_events e JOIN properties p ON p.id = e.property_id "
                    + "WHERE e.event_type = 'PAYMENT_FAILED' AND e.event_time >= :f AND e.event_time < :t" + sc + " GROUP BY e.code ORDER BY n DESC", ps);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("funnel", funnel);
            out.put("paymentFailuresByCode", failures);
            out.put("cancellation", cancellation);
            out.put("paymentAttemptsByStatus", attempts);
            out.put("timezone", zone.getId());
            out.put("coverageNote", "Funnel chỉ dựa trên event log (business_events); nếu event bị mất thì tỷ lệ chỉ phản ánh phần đã ghi nhận. Mẫu nhỏ -> tránh kết luận.");
            out.put("firstStepSample", first);
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }

    // ------------------------------------------------------------------ UC-23 dòng tiền / hoàn tiền / đối soát
    @Transactional(readOnly = true)
    public Map<String, Object> cashflow(AuthUser u, LocalDate from, LocalDate to, Long propertyId) {
        checkRange(from, to);
        Scope sc = scope(u);
        ZoneId zone = resolveZone(sc, propertyId);
        String key = keyPrefix(u) + ":cf:" + HmacSigner.sha256Hex(from + "|" + to + "|" + propertyId + "|" + zone).substring(0, 16);
        return cached(key, Map.class, () -> {
            MapSqlParameterSource ps = new MapSqlParameterSource("f", startOf(from, zone)).addValue("t", startOf(to, zone));
            String scSql = scopeSql(sc, ps, propertyId, "p");
            // payment: grain = payment. Thành công theo captured_at; đang xử lý/thất bại theo created_at
            Map<String, Object> pay = jdbc.queryForMap("SELECT "
                    + "COALESCE(SUM(pay.amount) FILTER (WHERE pay.status = 'SUCCEEDED' AND pay.captured_at >= :f AND pay.captured_at < :t),0) AS captured, "
                    + "COUNT(*) FILTER (WHERE pay.status = 'SUCCEEDED' AND pay.captured_at >= :f AND pay.captured_at < :t) AS captured_cnt, "
                    + "COALESCE(SUM(pay.amount) FILTER (WHERE pay.status IN ('INITIATED','PROCESSING','UNKNOWN','TIMED_OUT') AND pay.created_at >= :f AND pay.created_at < :t),0) AS in_flight, "
                    + "COUNT(*) FILTER (WHERE pay.status IN ('INITIATED','PROCESSING','UNKNOWN','TIMED_OUT') AND pay.created_at >= :f AND pay.created_at < :t) AS in_flight_cnt, "
                    + "COUNT(*) FILTER (WHERE pay.status = 'FAILED' AND pay.created_at >= :f AND pay.created_at < :t) AS failed_cnt "
                    + "FROM payments pay JOIN bookings b ON b.id = pay.booking_id JOIN properties p ON p.id = b.property_id WHERE 1=1" + scSql, ps);
            // refund: grain = refund. Thành công theo succeeded_at; pending/failed theo created_at, KHÔNG trừ vào thực thu
            Map<String, Object> ref = jdbc.queryForMap("SELECT "
                    + "COALESCE(SUM(r.amount) FILTER (WHERE r.status = 'SUCCEEDED' AND r.succeeded_at >= :f AND r.succeeded_at < :t),0) AS refunded, "
                    + "COUNT(*) FILTER (WHERE r.status = 'SUCCEEDED' AND r.succeeded_at >= :f AND r.succeeded_at < :t) AS refunded_cnt, "
                    + "COALESCE(SUM(r.amount) FILTER (WHERE r.status IN ('REQUESTED','PROCESSING') AND r.created_at >= :f AND r.created_at < :t),0) AS pending, "
                    + "COALESCE(SUM(r.amount) FILTER (WHERE r.status = 'FAILED' AND r.created_at >= :f AND r.created_at < :t),0) AS failed "
                    + "FROM refunds r JOIN bookings b ON b.id = r.booking_id JOIN properties p ON p.id = b.property_id WHERE 1=1" + scSql, ps);
            List<Map<String, Object>> cases = jdbc.queryForList("SELECT c.type AS type, COUNT(*) AS open_cases FROM reconciliation_cases c "
                    + "JOIN bookings b ON b.id = c.booking_id JOIN properties p ON p.id = b.property_id WHERE c.status = 'OPEN'" + scSql
                    + " GROUP BY c.type ORDER BY c.type", ps);
            BigDecimal captured = (BigDecimal) pay.get("captured");
            BigDecimal refunded = (BigDecimal) ref.get("refunded");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", Map.of("from", from.toString(), "to", to.toString(), "timezone", zone.getId(),
                    "semantics", "captured theo captured_at, refund theo succeeded_at, giao dịch đang xử lý/thất bại theo created_at"));
            out.put("currency", "VND");
            out.put("capturedAmount", captured);
            out.put("capturedPayments", ((Number) pay.get("captured_cnt")).longValue());
            out.put("inFlightAmount", pay.get("in_flight"));
            out.put("inFlightPayments", ((Number) pay.get("in_flight_cnt")).longValue());
            out.put("failedPayments", ((Number) pay.get("failed_cnt")).longValue());
            out.put("refundSucceededAmount", refunded);
            out.put("refundSucceededCount", ((Number) ref.get("refunded_cnt")).longValue());
            out.put("refundPendingAmount", ref.get("pending"));
            out.put("refundFailedAmount", ref.get("failed"));
            out.put("netCollected", captured.subtract(refunded));      // thực thu = captured - refund succeeded
            out.put("openReconciliationCases", cases);
            out.put("reconciliationStatus", cases.isEmpty() ? "CLEAN" : "NEEDS_ATTENTION");
            out.put("asOf", clock.instant().toString());
            out.put("sourceType", "LIVE");
            return out;
        });
    }
}
