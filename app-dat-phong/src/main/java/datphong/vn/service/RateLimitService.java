package datphong.vn.service;

import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Rate limit theo cửa sổ cố định bằng Redis INCR. Redis lỗi -> cho qua (fail-open) và đã log cảnh báo. */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final CacheService cache;

    public void check(String key, int limit, Duration window) {
        long n = cache.incr("rl:" + key, window);
        if (n > limit) throw new BusinessException(ErrorCode.RATE_LIMITED, "Bạn thao tác quá nhanh, vui lòng thử lại sau");
    }
}
