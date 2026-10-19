package datphong.vn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Version counter cho cache tìm kiếm/chi tiết: mọi thay đổi catalog/giá/tồn kho tăng version nên key cũ không còn được đọc.
 * Cache search chỉ mang tính tham khảo; bước giữ phòng luôn kiểm tra lại tồn kho và giá ở DB.
 */
@Service
@RequiredArgsConstructor
public class SearchVersionService {

    private static final String KEY = "search:version";
    private final CacheService cache;

    public long current() {
        return cache.getLong(KEY);
    }

    public void bump() {
        cache.incr(KEY, Duration.ofDays(30));
    }
}
