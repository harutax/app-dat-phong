package datphong.vn.service;

import datphong.vn.exception.entity.Notification;
import datphong.vn.exception.BusinessException;
import datphong.vn.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Thông báo trong hệ thống: lọc recipient_id bằng principal (không nhận id người nhận từ client). */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, int page, int size) {
        return repo.findByRecipientIdOrderByIdDesc(userId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    @Transactional(readOnly = true)
    public long unread(Long userId) {
        return repo.countByRecipientIdAndReadAtIsNull(userId);
    }

    /** Idempotent: đã đọc rồi thì giữ nguyên read_at. */
    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = repo.findByIdAndRecipientId(id, userId).orElseThrow(() -> BusinessException.notFound("Thông báo"));
        if (n.getReadAt() == null) n.setReadAt(clock.instant());
    }
}
