package datphong.vn.service;

import com.fasterxml.jackson.core.type.TypeReference;
import datphong.vn.config.AppProperties;
import datphong.vn.util.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis chỉ để tăng tốc. MỌI lỗi Redis đều bị nuốt (trả rỗng) - PostgreSQL luôn là nguồn đúng đắn.
 * Redis KHÔNG bảo đảm tồn kho.
 */
@Slf4j
@Service
public class CacheService {

    private final StringRedisTemplate redis;
    private final String prefix;

    public CacheService(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.prefix = props.getRedisPrefix();
    }

    private String k(String key) {
        return prefix + key;
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String v = redis.opsForValue().get(k(key));
            if (v == null) return Optional.empty();
            return Optional.of(Json.MAPPER.readValue(v, type));
        } catch (Exception e) {
            log.warn("redis get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void set(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(k(key), Json.write(value), ttl);
        } catch (Exception e) {
            log.warn("redis set failed key={}: {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            redis.delete(k(key));
        } catch (Exception e) {
            log.warn("redis delete failed key={}: {}", key, e.getMessage());
        }
    }

    /** Tăng counter (version cache / rate limit). Trả -1 nếu Redis lỗi. */
    public long incr(String key, Duration ttlIfNew) {
        try {
            Long v = redis.opsForValue().increment(k(key));
            if (v != null && v == 1L && ttlIfNew != null) redis.expire(k(key), ttlIfNew);
            return v == null ? -1 : v;
        } catch (Exception e) {
            log.warn("redis incr failed key={}: {}", key, e.getMessage());
            return -1;
        }
    }

    public long getLong(String key) {
        try {
            String v = redis.opsForValue().get(k(key));
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Lease ngắn chống cache stampede (single-flight). Không phải bảo đảm duy nhất. */
    public boolean tryLease(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k(key), "1", ttl));
        } catch (Exception e) {
            return true; // Redis lỗi: cho phép đi thẳng DB
        }
    }
}
