package datphong.vn.repository;

import datphong.vn.constant.ConversationType;
import datphong.vn.exception.entity.Conversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> lockById(@Param("id") Long id);

    @Query("select c from Conversation c where c.customerId = :cid and c.propertyId = :pid and c.type = :type "
            + "and ((:bid is null and c.bookingId is null) or c.bookingId = :bid) and c.status = 'OPEN'")
    Optional<Conversation> findActive(@Param("cid") Long customerId, @Param("pid") Long propertyId,
                                      @Param("type") ConversationType type, @Param("bid") Long bookingId);

    @Query("select count(c) from Conversation c where c.customerId = :cid and c.status = 'OPEN'")
    long countOpenByCustomer(@Param("cid") Long customerId);
}
