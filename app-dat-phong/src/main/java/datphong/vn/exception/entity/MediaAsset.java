package datphong.vn.exception.entity;

import datphong.vn.constant.MediaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Metadata ảnh ở PostgreSQL; binary ở MinIO. object_key sinh từ UUID phía server. */
@Entity
@Table(name = "media_assets")
@Getter
@Setter
@NoArgsConstructor
public class MediaAsset extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(name = "property_id", nullable = false)
    private Long propertyId;
    @Column(name = "room_type_id")
    private Long roomTypeId;
    @Column(name = "object_key", nullable = false, unique = true, length = 300)
    private String objectKey;
    @Column(name = "original_filename")
    private String originalFilename;
    @Column(name = "content_type", length = 100)
    private String contentType;
    @Column(name = "declared_size")
    private Long declaredSize;
    @Column(name = "size_bytes")
    private Long sizeBytes;
    @Column(length = 100)
    private String checksum;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaStatus status = MediaStatus.PENDING;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(nullable = false)
    private boolean cover;
    @Column(name = "expires_at")
    private Instant expiresAt;
}
