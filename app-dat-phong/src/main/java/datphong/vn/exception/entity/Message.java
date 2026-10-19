package datphong.vn.exception.entity;

import datphong.vn.constant.MessageType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Append-only. Không sửa/xóa cứng; nếu cần chỉ redaction có audit. */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;
    @Column(nullable = false)
    private long seq;
    @Column(name = "sender_id", nullable = false)
    private Long senderId;
    @Column(name = "client_message_id", nullable = false, length = 64)
    private String clientMessageId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Column(name = "asset_id")
    private Long assetId;
    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;
    @Column(nullable = false)
    private boolean redacted;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
