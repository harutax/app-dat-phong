package datphong.vn.exception.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** Người tham gia hội thoại + con trỏ đã đọc (last_read_seq): unread = last_message_seq - last_read_seq. */
@Entity
@Table(name = "conversation_participants")
@IdClass(ConversationParticipant.Pk.class)
@Getter
@Setter
@NoArgsConstructor
public class ConversationParticipant {
    @Id
    @Column(name = "conversation_id")
    private Long conversationId;
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false, length = 20)
    private String role;
    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

    public ConversationParticipant(Long conversationId, Long userId, String role) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
    }

    public static class Pk implements Serializable {
        private Long conversationId;
        private Long userId;

        public Pk() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Pk p && Objects.equals(conversationId, p.conversationId) && Objects.equals(userId, p.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conversationId, userId);
        }
    }
}
