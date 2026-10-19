package datphong.vn.service;

import datphong.vn.exception.entity.IdempotencyRecord;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Idempotency là HỢP ĐỒNG API: cùng (scope, key) + cùng requestHash -> trả kết quả cũ;
 * cùng key + khác payload -> IDEMPOTENCY_CONFLICT. Unique (scope, idem_key) ở DB là chốt an toàn cuối.
 * Bản ghi được ghi cùng transaction với kết quả nghiệp vụ nên rollback thì không còn dấu vết.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repo;
    private final Clock clock;

    /** @return resultRef của lần xử lý trước (nếu đã có và payload khớp). */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<String> lookup(String scope, String key, String requestHash) {
        return repo.findByScopeAndIdemKey(scope, key).map(r -> {
            if (!r.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key đã được dùng với nội dung khác");
            }
            return r.getResultRef();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void store(String scope, String key, String requestHash, String resultRef) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setScope(scope);
        r.setIdemKey(key);
        r.setRequestHash(requestHash);
        r.setResultRef(resultRef);
        r.setCreatedAt(clock.instant());
        repo.saveAndFlush(r); // flush để vi phạm unique nổ ngay trong transaction này
    }

    /** Đọc lại sau khi giao dịch cạnh tranh đã commit (dùng ở facade khi bắt DataIntegrityViolation). */
    @Transactional(readOnly = true)
    public Optional<String> lookupCommitted(String scope, String key, String requestHash) {
        return repo.findByScopeAndIdemKey(scope, key).map(r -> {
            if (!r.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key đã được dùng với nội dung khác");
            }
            return r.getResultRef();
        });
    }
}
