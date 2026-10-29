package datphong.vn.service;

import datphong.vn.constant.MediaStatus;
import datphong.vn.dto.request.CatalogRequests;
import datphong.vn.dto.response.CatalogResponses.MediaDto;
import datphong.vn.dto.response.CatalogResponses.UploadTicket;
import datphong.vn.exception.entity.MediaAsset;
import datphong.vn.exception.entity.Property;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.repository.MediaAssetRepository;
import datphong.vn.repository.RoomTypeRepository;
import io.minio.*;
import io.minio.Http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * UC-06. Binary ở MinIO, metadata ở PostgreSQL.
 * KHÔNG giữ transaction DB khi gọi MinIO: dùng TransactionTemplate cho từng bước DB ngắn,
 * trạng thái PENDING + finalize + job dọn object mồ côi bù cho việc hai hệ thống không rollback cùng nhau.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PER_PROPERTY = 30;
    private static final Map<String, String> EXT_BY_TYPE = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final Duration PENDING_TTL = Duration.ofMinutes(20);

    private final MinioClient minio;
    private final MediaAssetRepository repo;
    private final RoomTypeRepository roomTypes;
    private final CatalogService catalog;
    private final AuditService audit;
    private final SearchVersionService searchVersion;
    private final PlatformTransactionManager txManager;
    private final Clock clock;

    @Value("${minio.bucket}")
    private String bucket;
    @Value("${minio.public-url}")
    private String publicUrl;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** Bước 1: tạo bản ghi PENDING + presigned PUT ngắn hạn. Object key sinh từ UUID, không tin đường dẫn client. */
    public UploadTicket init(Long ownerId, CatalogRequests.UploadInit r) {
        String ext = EXT_BY_TYPE.get(r.contentType().toLowerCase());
        if (ext == null) throw new BusinessException(ErrorCode.MEDIA_INVALID, "Chỉ chấp nhận JPEG, PNG, WEBP");
        if (r.size() > MAX_SIZE) throw new BusinessException(ErrorCode.MEDIA_INVALID, "Ảnh tối đa 5MB");

        MediaAsset a = tx().execute(s -> {
            Property p = catalog.owned(ownerId, r.propertyId());
            if (r.roomTypeId() != null) {
                roomTypes.findByIdAndPropertyId(r.roomTypeId(), p.getId())
                        .orElseThrow(() -> BusinessException.validation("Loại phòng không thuộc cơ sở"));
            }
            long count = repo.countByPropertyIdAndStatusIn(p.getId(), List.of(MediaStatus.PENDING, MediaStatus.READY));
            if (count >= MAX_PER_PROPERTY) throw new BusinessException(ErrorCode.MEDIA_INVALID, "Đã đạt giới hạn số ảnh");
            MediaAsset m = new MediaAsset();
            m.setOwnerId(ownerId);
            m.setPropertyId(p.getId());
            m.setRoomTypeId(r.roomTypeId());
            m.setObjectKey("properties/" + p.getId() + "/" + UUID.randomUUID() + "." + ext);
            m.setOriginalFilename(sanitizeName(r.filename()));
            m.setContentType(r.contentType().toLowerCase());
            m.setDeclaredSize(r.size());
            m.setStatus(MediaStatus.PENDING);
            m.setExpiresAt(clock.instant().plus(PENDING_TTL));
            return repo.save(m);
        });
        try {
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT).bucket(bucket).object(a.getObjectKey()).expiry(10, TimeUnit.MINUTES).build());
            return new UploadTicket(a.getId(), a.getObjectKey(), rewrite(url), a.getExpiresAt());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE, "Kho ảnh tạm thời không khả dụng");
        }
    }

    /** Bước 2: HEAD object, kiểm tra size/MIME thực tế (magic bytes) rồi chuyển READY. */
    public MediaDto finalizeUpload(Long ownerId, Long assetId) {
        MediaAsset a = tx().execute(s -> {
            MediaAsset m = repo.findById(assetId).orElseThrow(() -> BusinessException.forbidden("Ảnh không thuộc quyền của bạn"));
            catalog.owned(ownerId, m.getPropertyId());
            return m;
        });
        if (a.getStatus() == MediaStatus.READY) return toDto(a);
        if (a.getStatus() != MediaStatus.PENDING) throw BusinessException.conflict("Ảnh không ở trạng thái chờ hoàn tất");
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException(ErrorCode.MEDIA_INVALID, "Lượt upload đã hết hạn");
        }
        try {
            StatObjectResponse st = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(a.getObjectKey()).build());
            if (st.size() > MAX_SIZE || st.size() <= 0) throw new BusinessException(ErrorCode.MEDIA_INVALID, "Dung lượng ảnh không hợp lệ");
            byte[] head = new byte[12];
            try (InputStream in = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(a.getObjectKey()).offset(0L).length(12L).build())) {
                int n = in.readNBytes(head, 0, 12);
                if (n < 12 || !magicMatches(a.getContentType(), head)) throw new BusinessException(ErrorCode.MEDIA_INVALID, "Nội dung không đúng định dạng ảnh");
            }
            String etag = st.etag();
            long size = st.size();
            MediaAsset out = tx().execute(s -> {
                MediaAsset m = repo.findById(assetId).orElseThrow();
                if (m.getStatus() != MediaStatus.PENDING) return m; // idempotent
                m.setSizeBytes(size);
                m.setChecksum(etag);
                m.setStatus(MediaStatus.READY);
                m.setExpiresAt(null);
                m.setSortOrder((int) repo.countByPropertyIdAndStatusIn(m.getPropertyId(), List.of(MediaStatus.READY)));
                if (!repo.existsByPropertyIdAndStatusAndCoverTrue(m.getPropertyId(), MediaStatus.READY) && m.getRoomTypeId() == null) {
                    m.setCover(true);
                }
                repo.save(m);
                audit.record("MEDIA_READY", "MEDIA", m.getId(), "PENDING", "READY", null, null);
                searchVersion.bump();
                return m;
            });
            return toDto(out);
        } catch (BusinessException e) {
            markFailed(assetId);
            throw e;
        } catch (Exception e) {
            // Chưa upload / MinIO chậm: giữ PENDING để client thử lại tới khi hết TTL.
            log.warn("finalize media {} failed: {}", assetId, e.getMessage());
            throw new BusinessException(ErrorCode.MEDIA_INVALID, "Chưa tìm thấy tệp đã upload, hãy thử lại");
        }
    }

    private void markFailed(Long id) {
        tx().executeWithoutResult(s -> repo.findById(id).ifPresent(m -> m.setStatus(MediaStatus.FAILED)));
    }

    public void setCover(Long ownerId, Long assetId) {
        tx().executeWithoutResult(s -> {
            MediaAsset m = repo.findById(assetId).orElseThrow(() -> BusinessException.forbidden("Ảnh không thuộc quyền của bạn"));
            catalog.owned(ownerId, m.getPropertyId());
            if (m.getStatus() != MediaStatus.READY) throw BusinessException.conflict("Ảnh chưa sẵn sàng");
            repo.findByPropertyIdAndStatusOrderBySortOrderAscIdAsc(m.getPropertyId(), MediaStatus.READY).forEach(x -> x.setCover(x.getId().equals(assetId)));
            searchVersion.bump();
        });
    }

    /** Xóa: đổi DELETING ngay, xóa object bất đồng bộ qua job dọn dẹp. */
    public void delete(Long ownerId, Long assetId) {
        tx().executeWithoutResult(s -> {
            MediaAsset m = repo.findById(assetId).orElseThrow(() -> BusinessException.forbidden("Ảnh không thuộc quyền của bạn"));
            catalog.owned(ownerId, m.getPropertyId());
            if (m.getStatus() == MediaStatus.DELETING || m.getStatus() == MediaStatus.DELETED) return;
            m.setStatus(MediaStatus.DELETING);
            m.setCover(false);
            audit.record("MEDIA_DELETE", "MEDIA", m.getId(), null, "DELETING", null, null);
            searchVersion.bump();
        });
    }

    public List<MediaDto> list(Long propertyId) {
        return repo.findByPropertyIdAndStatusOrderBySortOrderAscIdAsc(propertyId, MediaStatus.READY).stream().map(this::toDto).toList();
    }

    /** URL xem có hạn (bucket private). Presigned URL không phải token quyền vĩnh viễn. */
    public String viewUrl(String objectKey) {
        try {
            return rewrite(minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(objectKey).expiry(1, TimeUnit.HOURS).build()));
        } catch (Exception e) {
            return null;
        }
    }

    private MediaDto toDto(MediaAsset m) {
        return new MediaDto(m.getId(), m.getStatus().name(), m.isCover(), m.getSortOrder(), viewUrl(m.getObjectKey()));
    }

    /** Job dọn: PENDING hết hạn -> PENDING_EXPIRED; DELETING/PENDING_EXPIRED/FAILED -> xóa object -> DELETED. */
    public void cleanup() {
        tx().executeWithoutResult(s -> repo.findExpiredPending(clock.instant()).forEach(m -> m.setStatus(MediaStatus.PENDING_EXPIRED)));
        for (MediaAsset m : repo.findTop100ByStatusIn(List.of(MediaStatus.DELETING, MediaStatus.PENDING_EXPIRED, MediaStatus.FAILED))) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(m.getObjectKey()).build());
                Long id = m.getId();
                tx().executeWithoutResult(s -> repo.findById(id).ifPresent(x -> x.setStatus(MediaStatus.DELETED)));
            } catch (Exception e) {
                log.warn("cleanup media {} failed: {}", m.getId(), e.getMessage());
            }
        }
    }

    private String rewrite(String url) {
        // Presigned URL ký theo host nội bộ; trong dev có thể đặt MINIO_PUBLIC_URL = URL nội bộ để chữ ký hợp lệ.
        return url;
    }

    private static String sanitizeName(String n) {
        String base = n.replaceAll("[\\\\/]", "_").replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return base.substring(0, Math.min(200, base.length()));
    }

    private static boolean magicMatches(String contentType, byte[] h) {
        return switch (contentType) {
            case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "image/png" -> (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
            case "image/webp" -> h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
            default -> false;
        };
    }
}
