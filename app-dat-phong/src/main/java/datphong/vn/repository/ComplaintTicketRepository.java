package datphong.vn.repository;

import datphong.vn.exception.entity.ComplaintTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ComplaintTicketRepository extends JpaRepository<ComplaintTicket, Long> {
    List<ComplaintTicket> findByConversationIdOrderByIdDesc(Long conversationId);

    @Query("select count(t) > 0 from ComplaintTicket t where t.conversationId = :cid and t.status in "
            + "(datphong.vn.constant.TicketStatus.OPEN, datphong.vn.constant.TicketStatus.ACKNOWLEDGED, datphong.vn.constant.TicketStatus.IN_PROGRESS)")
    boolean existsOpen(@Param("cid") Long conversationId);
}
