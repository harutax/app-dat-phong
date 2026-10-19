package datphong.vn.repository;

import datphong.vn.exception.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByConversationIdAndSenderIdAndClientMessageId(Long conversationId, Long senderId, String clientMessageId);

    /** Cursor pagination theo (conversation_id, seq): ổn định, không OFFSET sâu. */
    @Query("select m from Message m where m.conversationId = :cid and m.seq > :after order by m.seq asc")
    List<Message> findAfter(@Param("cid") Long conversationId, @Param("after") long afterSeq, Pageable pageable);

    @Query("select m from Message m where m.conversationId = :cid and m.seq < :before order by m.seq desc")
    List<Message> findBefore(@Param("cid") Long conversationId, @Param("before") long beforeSeq, Pageable pageable);
}
