package datphong.vn.repository;

import datphong.vn.exception.entity.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationParticipantRepository extends JpaRepository<ConversationParticipant, ConversationParticipant.Pk> {
    List<ConversationParticipant> findByConversationId(Long conversationId);

    Optional<ConversationParticipant> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ConversationParticipant> findByUserId(Long userId);

    /** Đánh dấu đã đọc idempotent và race-safe: con trỏ chỉ tiến lên, không bao giờ lùi. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConversationParticipant p set p.lastReadSeq = :seq where p.conversationId = :cid and p.userId = :uid and p.lastReadSeq < :seq")
    int advanceRead(@Param("cid") Long conversationId, @Param("uid") Long userId, @Param("seq") long seq);
}
