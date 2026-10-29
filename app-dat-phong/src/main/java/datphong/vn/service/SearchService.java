package datphong.vn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.response.SearchResponses.*;
import datphong.vn.exception.entity.MediaAsset;
import datphong.vn.exception.BusinessException;
import datphong.vn.repository.MediaAssetRepository;
import datphong.vn.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UC-07. SQL động có bind params + sort whitelist (không nối chuỗi từ input), phân trang ổn định (sort + khóa id),
 * không N+1 (3 truy vấn: trang property, room type của trang, ảnh bìa). Cache Redis TTL ngắn, key chứa version.
 * Kết quả chỉ là tham khảo - bước giữ phòng kiểm tra lại tồn kho và giá.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final Map<String, String> SORTS = Map.of(
            "price_asc", "min_total ASC",
            "price_desc", "min_total DESC",
            "rating", "rating DESC",
            "name", "p.name ASC");

    private final NamedParameterJdbcTemplate jdbc;
    private final CacheService cache;
    private final SearchVersionService versions;
    private final MediaAssetRepository media;
    private final MediaService mediaService;
    private final Clock clock;

    public record Filter(String city, LocalDate checkIn, LocalDate checkOut, int guests, List<String> amenities,
                         BigDecimal minPrice, BigDecimal maxPrice, String propertyType, String sort, int page, int size) {}

    private void validate(Filter f) {
        if (f.checkIn() == null || f.checkOut() == null) throw BusinessException.validation("Cần checkIn và checkOut");
        if (!f.checkOut().isAfter(f.checkIn())) throw BusinessException.validation("checkOut phải sau checkIn");
        // múi giờ cơ sở khác nhau: dùng múi giờ mặc định làm ngưỡng, kiểm tra chặt theo property ở bước quote/booking
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh")));
        if (f.checkIn().isBefore(today.minusDays(1))) throw BusinessException.validation("Ngày nhận phòng ở quá khứ");
        if (ChronoUnit.DAYS.between(f.checkIn(), f.checkOut()) > PricingService.MAX_NIGHTS) throw BusinessException.validation("Khoảng lưu trú quá dài");
        if (f.guests() < 1 || f.guests() > 50) throw BusinessException.validation("Số khách không hợp lệ");
        if (f.size() < 1 || f.size() > 50) throw BusinessException.validation("size tối đa 50");
        if (f.page() < 0 || f.page() > 1000) throw BusinessException.validation("page không hợp lệ");
        // UC-07: sort chỉ nhận giá trị trong whitelist; giá trị lạ là lỗi đầu vào, không âm thầm đổi sang mặc định
        if (f.sort() != null && !f.sort().isBlank() && !SORTS.containsKey(f.sort())) {
            throw BusinessException.validation("sort phải là một trong " + new java.util.TreeSet<>(SORTS.keySet()));
        }
        if (f.minPrice() != null && f.maxPrice() != null && f.minPrice().compareTo(f.maxPrice()) > 0) throw BusinessException.validation("Khoảng giá không hợp lệ");
        if (f.amenities() != null && f.amenities().size() > 12) throw BusinessException.validation("Quá nhiều bộ lọc tiện ích");
    }

    private record Built(String cte, MapSqlParameterSource params, String priceFilter, int nights) {}

    private Built build(Filter f) {
        int nights = (int) ChronoUnit.DAYS.between(f.checkIn(), f.checkOut());
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("in", f.checkIn()).addValue("out", f.checkOut()).addValue("guests", f.guests()).addValue("nights", nights);
        StringBuilder filters = new StringBuilder();
        if (f.city() != null && !f.city().isBlank()) {
            filters.append(" AND lower(p.city) LIKE :city");
            ps.addValue("city", "%" + f.city().trim().toLowerCase() + "%");
        }
        if (f.propertyType() != null && !f.propertyType().isBlank()) {
            filters.append(" AND p.property_type = :ptype");
            ps.addValue("ptype", f.propertyType());
        }
        if (f.amenities() != null && !f.amenities().isEmpty()) {
            filters.append(" AND (SELECT COUNT(DISTINCT am.id) FROM property_amenities pa JOIN amenities am ON am.id = pa.amenity_id "
                    + "WHERE pa.property_id = p.id AND am.code IN (:amenities)) = :amenityCount");
            ps.addValue("amenities", f.amenities());
            ps.addValue("amenityCount", new HashSet<>(f.amenities()).size());
        }
        StringBuilder price = new StringBuilder();
        if (f.minPrice() != null) {
            price.append(" AND (a.subtotal / :nights) >= :minPrice");
            ps.addValue("minPrice", f.minPrice());
        }
        if (f.maxPrice() != null) {
            price.append(" AND (a.subtotal / :nights) <= :maxPrice");
            ps.addValue("maxPrice", f.maxPrice());
        }
        String cte = """
                WITH avail AS (
                  SELECT p.id AS property_id, rt.id AS room_type_id, rt.name AS room_type_name, rt.capacity, rt.bed_type,
                         COUNT(r.id) AS available_rooms, MIN(r.id) AS sample_room_id,
                         (SELECT SUM(x.amount) FROM room_rates x WHERE x.room_type_id = rt.id
                             AND x.stay_date >= CAST(:in AS date) AND x.stay_date < CAST(:out AS date)) AS subtotal
                  FROM properties p
                  JOIN room_types rt ON rt.property_id = p.id AND rt.status = 'ACTIVE'
                  JOIN rooms r ON r.room_type_id = rt.id AND r.status = 'ACTIVE'
                  WHERE p.status = 'PUBLISHED' AND rt.capacity >= :guests
                """ + filters + """
                    AND (SELECT COUNT(*) FROM room_rates x WHERE x.room_type_id = rt.id
                         AND x.stay_date >= CAST(:in AS date) AND x.stay_date < CAST(:out AS date)) = :nights
                    AND NOT EXISTS (SELECT 1 FROM room_allocations al WHERE al.room_id = r.id AND al.active
                         AND al.stay_range && daterange(CAST(:in AS date), CAST(:out AS date), '[)'))
                  GROUP BY p.id, rt.id
                )
                """;
        return new Built(cte, ps, price.toString(), nights);
    }

    @Transactional(readOnly = true)
    public SearchPage search(Filter f) {
        validate(f);
        String sort = SORTS.getOrDefault(f.sort() == null ? "price_asc" : f.sort(), SORTS.get("price_asc"));
        String key = "search:v" + versions.current() + ":" + HmacSigner.sha256Hex(Json.write(f) + sort);
        Optional<SearchPage> hit = cache.get(key, new TypeReference<SearchPage>() {});
        if (hit.isPresent()) {
            SearchPage h = hit.get();
            return new SearchPage(h.items(), h.total(), h.page(), h.size(), h.asOf(), true, h.checkIn(), h.checkOut(), h.guests());
        }
        Built b = build(f);

        Long total = jdbc.queryForObject(b.cte() + "SELECT COUNT(DISTINCT a.property_id) FROM avail a WHERE 1=1 " + b.priceFilter(),
                b.params(), Long.class);

        MapSqlParameterSource ps = copy(b.params()).addValue("size", f.size()).addValue("offset", (long) f.page() * f.size());
        List<Map<String, Object>> props = jdbc.queryForList(b.cte() + """
                SELECT p.id, p.name, p.city, p.address, p.latitude, p.longitude, p.property_type,
                       MIN(a.subtotal) AS min_total, SUM(a.available_rooms) AS rooms_left,
                       COALESCE((SELECT AVG(rv.rating) FROM reviews rv WHERE rv.property_id = p.id AND rv.status = 'PUBLISHED'), 0) AS rating,
                       (SELECT COUNT(*) FROM reviews rv WHERE rv.property_id = p.id AND rv.status = 'PUBLISHED') AS review_count
                FROM avail a JOIN properties p ON p.id = a.property_id
                WHERE 1=1 """ + b.priceFilter() + " GROUP BY p.id ORDER BY " + sort + ", p.id ASC LIMIT :size OFFSET :offset", ps);

        List<Long> ids = props.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        Map<Long, List<RoomTypeOffer>> offers = new HashMap<>();
        Map<Long, String> covers = new HashMap<>();
        if (!ids.isEmpty()) {
            MapSqlParameterSource ps2 = copy(b.params()).addValue("ids", ids);
            jdbc.queryForList(b.cte() + "SELECT * FROM avail a WHERE a.property_id IN (:ids) " + b.priceFilter() + " ORDER BY a.property_id, a.subtotal", ps2)
                    .forEach(m -> {
                        BigDecimal sub = (BigDecimal) m.get("subtotal");
                        offers.computeIfAbsent(((Number) m.get("property_id")).longValue(), k -> new ArrayList<>()).add(new RoomTypeOffer(
                                ((Number) m.get("room_type_id")).longValue(), (String) m.get("room_type_name"), ((Number) m.get("capacity")).intValue(),
                                (String) m.get("bed_type"), ((Number) m.get("available_rooms")).intValue(), ((Number) m.get("sample_room_id")).longValue(),
                                sub, sub.divide(BigDecimal.valueOf(b.nights()), 0, java.math.RoundingMode.HALF_UP)));
                    });
            for (MediaAsset ma : media.findCovers(ids)) covers.put(ma.getPropertyId(), mediaService.viewUrl(ma.getObjectKey()));
        }
        List<PropertyCard> items = props.stream().map(m -> {
            Long id = ((Number) m.get("id")).longValue();
            return new PropertyCard(id, (String) m.get("name"), (String) m.get("city"), (String) m.get("address"),
                    (BigDecimal) m.get("latitude"), (BigDecimal) m.get("longitude"), (String) m.get("property_type"),
                    (BigDecimal) m.get("min_total"), ((Number) m.get("rooms_left")).intValue(),
                    Math.round(((Number) m.get("rating")).doubleValue() * 10) / 10.0, ((Number) m.get("review_count")).longValue(),
                    covers.get(id), offers.getOrDefault(id, List.of()));
        }).collect(Collectors.toList());

        SearchPage page = new SearchPage(items, total == null ? 0 : total, f.page(), f.size(), clock.instant(), false,
                f.checkIn(), f.checkOut(), f.guests());
        cache.set(key, page, Duration.ofSeconds(60));
        return page;
    }

    /** Danh sách phẳng theo loại phòng (GET /rooms/search). */
    @Transactional(readOnly = true)
    public FlatPage searchRooms(Filter f) {
        validate(f);
        Built b = build(f);
        Long total = jdbc.queryForObject(b.cte() + "SELECT COUNT(*) FROM avail a WHERE 1=1 " + b.priceFilter(), b.params(), Long.class);
        MapSqlParameterSource ps = copy(b.params()).addValue("size", f.size()).addValue("offset", (long) f.page() * f.size());
        List<FlatOffer> items = jdbc.queryForList(b.cte() + "SELECT a.*, p.name AS property_name, p.city FROM avail a JOIN properties p ON p.id = a.property_id "
                + "WHERE 1=1 " + b.priceFilter() + " ORDER BY a.subtotal ASC, a.room_type_id ASC LIMIT :size OFFSET :offset", ps).stream().map(m -> {
            BigDecimal sub = (BigDecimal) m.get("subtotal");
            return new FlatOffer(((Number) m.get("property_id")).longValue(), (String) m.get("property_name"), (String) m.get("city"),
                    ((Number) m.get("room_type_id")).longValue(), (String) m.get("room_type_name"), ((Number) m.get("capacity")).intValue(),
                    ((Number) m.get("available_rooms")).intValue(), ((Number) m.get("sample_room_id")).longValue(), sub,
                    sub.divide(BigDecimal.valueOf(b.nights()), 0, java.math.RoundingMode.HALF_UP));
        }).toList();
        return new FlatPage(items, total == null ? 0 : total, f.page(), f.size(), clock.instant());
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource src) {
        MapSqlParameterSource c = new MapSqlParameterSource();
        for (String n : src.getParameterNames()) c.addValue(n, src.getValue(n));
        return c;
    }
}
