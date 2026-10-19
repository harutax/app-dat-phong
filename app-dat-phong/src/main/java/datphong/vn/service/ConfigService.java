package datphong.vn.service;

import datphong.vn.exception.entity.ConfigChangeLog;
import datphong.vn.exception.entity.ConfigDefinition;
import datphong.vn.exception.entity.ConfigValue;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.ConfigChangeLogRepository;
import datphong.vn.repository.ConfigDefinitionRepository;
import datphong.vn.repository.ConfigValueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cấu hình động (GLOBAL scope). Cache trong bộ nhớ tải lại mỗi 15 giây và ngay khi ghi, nên các instance
 * lệch tối đa ~15s (đây là độ trễ có chủ đích, version + audit giúp phát hiện). Giá trị chỉ có hiệu lực từ effective_at.
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigDefinitionRepository defs;
    private final ConfigValueRepository values;
    private final ConfigChangeLogRepository logs;
    private final Clock clock;
    private final AuditService audit;

    private volatile Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long loadedAtMs = 0;

    private Map<String, String> snapshot() {
        if (System.currentTimeMillis() - loadedAtMs > 15_000) reload();
        return cache;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public synchronized void reload() {
        Map<String, String> m = new HashMap<>();
        Instant now = clock.instant();
        defs.findAll().forEach(d -> m.put(d.getKey(), d.getDefaultValue()));
        values.findAll().forEach(v -> {
            if (v.getScope().equals("GLOBAL") && !v.getEffectiveAt().isAfter(now)) m.put(v.getKey(), v.getValue());
        });
        this.cache = m;
        this.loadedAtMs = System.currentTimeMillis();
    }

    public String getString(String key) {
        String v = snapshot().get(key);
        if (v == null) throw new IllegalStateException("Thiếu cấu hình " + key);
        return v;
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public BigDecimal getDecimal(String key) {
        return new BigDecimal(getString(key));
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public Map<String, String> publicConfig() {
        Map<String, String> out = new LinkedHashMap<>();
        defs.findAll().stream().filter(ConfigDefinition::isPublicFlag).forEach(d -> out.put(d.getKey(), getString(d.getKey())));
        return out;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigDefinition d : defs.findAll()) {
            Optional<ConfigValue> v = values.findByKeyAndScopeAndScopeId(d.getKey(), "GLOBAL", "");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.getKey());
            m.put("type", d.getValueType());
            m.put("scope", d.getScope());
            m.put("min", d.getMinValue());
            m.put("max", d.getMaxValue());
            m.put("value", v.map(ConfigValue::getValue).orElse(d.getDefaultValue()));
            m.put("version", v.map(ConfigValue::getVersion).orElse(0));
            m.put("effectiveAt", v.map(ConfigValue::getEffectiveAt).orElse(null));
            m.put("description", d.getDescription());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("key")));
        return out;
    }

    /** Cập nhật nhiều khóa trong một transaction, validate kiểu/biên/chéo trường rồi ghi version + change log + audit. */
    @Transactional
    public void update(Map<String, String> changes, Instant effectiveAt, String reason, Long actorId) {
        if (changes == null || changes.isEmpty()) throw BusinessException.validation("Không có thay đổi");
        Instant eff = effectiveAt == null ? clock.instant() : effectiveAt;
        if (eff.isBefore(clock.instant().minusSeconds(60))) throw BusinessException.validation("effectiveAt không được ở quá khứ");

        Map<String, String> merged = new HashMap<>(snapshot());
        for (var e : changes.entrySet()) {
            ConfigDefinition d = defs.findByKey(e.getKey())
                    .orElseThrow(() -> BusinessException.validation("Khóa cấu hình không tồn tại: " + e.getKey()));
            validate(d, e.getValue());
            merged.put(e.getKey(), e.getValue());
        }
        // Ràng buộc chéo: không cấu hình vô nghĩa
        int min = Integer.parseInt(merged.get("auth.password.minLength"));
        int max = Integer.parseInt(merged.get("auth.password.maxLength"));
        if (min > max) throw BusinessException.validation("auth.password.minLength phải <= auth.password.maxLength");

        for (var e : changes.entrySet()) {
            ConfigValue v = values.findByKeyAndScopeAndScopeId(e.getKey(), "GLOBAL", "").orElse(null);
            String old = v == null ? defs.findByKey(e.getKey()).get().getDefaultValue() : v.getValue();
            if (v == null) {
                v = new ConfigValue();
                v.setKey(e.getKey());
                v.setVersion(1);
            } else {
                v.setVersion(v.getVersion() + 1);
            }
            v.setValue(e.getValue());
            v.setEffectiveAt(eff);
            v.setUpdatedBy(actorId);
            v.setUpdatedAt(clock.instant());
            values.save(v);

            ConfigChangeLog l = new ConfigChangeLog();
            l.setKey(e.getKey());
            l.setOldValue(old);
            l.setNewValue(e.getValue());
            l.setVersion(v.getVersion());
            l.setChangedBy(actorId);
            l.setReason(reason);
            l.setAt(clock.instant());
            logs.save(l);
            audit.record("CONFIG_UPDATE", "CONFIG", e.getKey(), old, e.getValue(), reason, null);
        }
        reload();
    }

    private void validate(ConfigDefinition d, String raw) {
        try {
            switch (d.getValueType()) {
                case "INT" -> {
                    long n = Long.parseLong(raw);
                    checkRange(d, BigDecimal.valueOf(n));
                }
                case "DECIMAL" -> checkRange(d, new BigDecimal(raw));
                case "BOOL" -> {
                    if (!raw.equals("true") && !raw.equals("false")) throw new NumberFormatException();
                }
                default -> {
                    if (raw == null || raw.isBlank()) throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException ex) {
            throw BusinessException.validation("Giá trị không hợp lệ cho " + d.getKey());
        }
    }

    private void checkRange(ConfigDefinition d, BigDecimal v) {
        if (d.getMinValue() != null && v.compareTo(d.getMinValue()) < 0
                || d.getMaxValue() != null && v.compareTo(d.getMaxValue()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    d.getKey() + " phải trong khoảng [" + d.getMinValue() + ", " + d.getMaxValue() + "]");
        }
    }
}
