package datphong.vn.service;

import datphong.vn.constant.*;
import datphong.vn.domain.HmacSigner;
import datphong.vn.dto.request.MessagingRequests;
import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.exception.entity.*;
import datphong.vn.repository.*;
import datphong.vn.security.AuthUser;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * UC-27/28. PostgreSQL là nguồn sự thật của tin nhắn (append-only, seq theo hội thoại, cursor).
 * Realtime (SSE/WebSocket) nếu có chỉ là tăng trải nghiệm; client phục hồi bằng GET messages?afterSeq=...
 * Không ghi nội dung tin nhắn vào log/metric/URL.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversations;
    private final ConversationParticipantRepository participants;
    private final MessageRepository messages;
    private final ComplaintTicketRepository tickets;
    private final PropertyRepository properties;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final OutboxService outbox;
    private final RateLimitService rateLimit;
    private final ConfigService config;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager txm;
    private final Clock clock;

    // ------------------------------------------------------------------ quyền truy cập
    /** Bắt buộc là participant HIỆN TẠI: customer, HOST đang sở hữu property, hoặc SUPPORT được phân công. Không truy vấn theo conversation_id đơn thuần. */
    public Conversation requireAccess(AuthUser u, Long conversationId) {
        Conversation c = conversations.findById(conversationId).orElseThrow(() -> BusinessException.notFound("Hội thoại"));
        if (c.getCustomerId().equals(u.getId())) return c;
        Optional<ConversationParticipant> cp = participants.findByConversationIdAndUserId(conversationId, u.getId());
        if (cp.isPresent()) {
            if (cp.get().getRole().equals("HOST")) {
                // chủ cũ của property không còn thấy hội thoại
                boolean stillOwner = properties.findByIdAndOwnerId(c.getPropertyId(), u.getId()).isPresent();
                if (stillOwner) return c;
            } else if (cp.get().getRole().equals("SUPPORT") && u.hasPermission("CONVERSATION_SUPPORT")) {
                return c;
            }
        }
        throw BusinessException.notFound("Hội thoại"); // ẩn tài nguyên
    }

    // ------------------------------------------------------------------ mở hội thoại
    public Map<String, Object> open(Long customerId, MessagingRequests.OpenConversation r) {
        ConversationType type;
        try { type = ConversationType.valueOf(r.type()); } catch (Exception e) { throw BusinessException.validation("type phải là INQUIRY | BOOKING_SUPPORT"); }
        rateLimit.check("conv:open:" + customerId, 10, Duration.ofHours(1));
        TransactionTemplate tx = new TransactionTemplate(txm);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return tx.execute(s -> openTx(customerId, type, r));
            } catch (DataIntegrityViolationException e) {
                // hai request khởi tạo song song: unique active key chặn bản thứ hai; lượt sau sẽ tìm thấy hội thoại đã tạo
                if (attempt == 1) throw e;
            }
        }
        throw new IllegalStateException();
    }

    private Map<String, Object> openTx(Long customerId, ConversationType type, MessagingRequests.OpenConversation r) {
        Property p;
        Long bookingId = r.bookingId();
        if (bookingId != null) {
            Booking b = bookings.findByIdAndCustomerId(bookingId, customerId).orElseThrow(() -> BusinessException.notFound("Booking"));
            p = properties.findById(b.getPropertyId()).orElseThrow();
        } else {
            if (type == ConversationType.BOOKING_SUPPORT) throw BusinessException.validation("BOOKING_SUPPORT cần bookingId");
            p = properties.findById(r.propertyId()).filter(x -> x.getStatus() == PropertyStatus.PUBLISHED)
                    .orElseThrow(() -> BusinessException.notFound("Cơ sở"));
        }
        if (bookingId != null && !p.getId().equals(r.propertyId())) throw BusinessException.validation("propertyId không khớp booking");
        User me = users.findById(customerId).orElseThrow();
        if (me.getStatus() != UserStatus.ACTIVE) throw BusinessException.forbidden("Tài khoản không được phép tạo hội thoại");

        Conversation c = conversations.findActive(customerId, p.getId(), type, bookingId).orElse(null);
        if (c == null) {
            if (conversations.countOpenByCustomer(customerId) >= config.getInt("conversation.maxOpen")) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "Bạn đang có quá nhiều hội thoại mở");
            }
            c = new Conversation();
            c.setPropertyId(p.getId());
            c.setBookingId(bookingId);
            c.setCustomerId(customerId);
            c.setHostId(p.getOwnerId());                // theo owner THỰC TẾ, không tin client
            c.setType(type);
            conversations.saveAndFlush(c);              // unique index uq_conv_active
            participants.save(new ConversationParticipant(c.getId(), customerId, "CUSTOMER"));
            participants.save(new ConversationParticipant(c.getId(), p.getOwnerId(), "HOST"));
        }
        Conversation locked = conversations.lockById(c.getId()).orElseThrow();
        Message m = appendMessage(locked, customerId, MessageType.NORMAL, r.firstMessage(), r.clientMessageId());
        return Map.of("conversationId", locked.getId(), "type", locked.getType().name(), "status", locked.getStatus(),
                "lastSeq", locked.getLastMessageSeq(), "firstMessageSeq", m.getSeq(), "bookingId", String.valueOf(bookingId));
    }

    // ------------------------------------------------------------------ gửi tin
    public Message send(AuthUser u, Long conversationId, MessagingRequests.SendMessage r) {
        rateLimit.check("msg:" + u.getId(), 30, Duration.ofMinutes(1));
        requireAccess(u, conversationId);
        MessageType type = MessageType.NORMAL;
        if (r.type() != null) {
            try { type = MessageType.valueOf(r.type()); } catch (Exception e) { throw BusinessException.validation("type không hợp lệ"); }
            if (type == MessageType.COMPLAINT) throw BusinessException.validation("Dùng endpoint complaints để tạo phản ánh");
        }
        MessageType finalType = type;
        TransactionTemplate tx = new TransactionTemplate(txm);
        return tx.execute(s -> {
            Conversation c = conversations.lockById(conversationId).orElseThrow();   // tuần tự hóa seq trong hội thoại
            return appendMessage(c, u.getId(), finalType, r.body(), r.clientMessageId());
        });
    }

    /** Gọi khi đã giữ khóa hàng conversation. Idempotent theo (conversation, sender, clientMessageId). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Message appendMessage(Conversation c, Long senderId, MessageType type, String rawBody, String clientMessageId) {
        String body = Texts.stripHtml(rawBody);
        if (body == null || body.isBlank()) throw BusinessException.validation("Nội dung trống");
        if (body.length() > 2000) throw BusinessException.validation("Nội dung quá dài");
        String hash = HmacSigner.sha256Hex(type + "|" + body);
        var existing = messages.findByConversationIdAndSenderIdAndClientMessageId(c.getId(), senderId, clientMessageId);
        if (existing.isPresent()) {
            if (!existing.get().getBodyHash().equals(hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "clientMessageId đã dùng với nội dung khác");
            return existing.get();       // gửi lại do mạng retry: trả bản ghi cũ
        }
        if (!c.getStatus().equals("OPEN")) throw BusinessException.conflict("Hội thoại đã đóng");
        long seq = c.getLastMessageSeq() + 1;
        Message m = new Message();
        m.setConversationId(c.getId());
        m.setSeq(seq);
        m.setSenderId(senderId);
        m.setClientMessageId(clientMessageId);
        m.setType(type);
        m.setBody(body);
        m.setBodyHash(hash);
        m.setCreatedAt(clock.instant());
        messages.saveAndFlush(m);
        c.setLastMessageSeq(seq);
        conversations.save(c);
        participants.advanceRead(c.getId(), senderId, seq);      // người gửi đã "đọc" tin của mình
        // message + outbox cùng transaction; giao realtime/notification là side effect sau commit
        outbox.publish("MESSAGE_SENT:" + c.getId() + ":" + seq, "MESSAGE_SENT", "CONVERSATION", c.getId(),
                Map.of("conversationId", c.getId(), "senderId", senderId, "seq", seq));
        return m;
    }

    // ------------------------------------------------------------------ đọc
    @Transactional(readOnly = true)
    public Map<String, Object> messages(AuthUser u, Long conversationId, Long afterSeq, Long beforeSeq, int limit) {
        Conversation c = requireAccess(u, conversationId);
        int lim = Math.min(Math.max(1, limit), 100);
        List<Message> list = beforeSeq != null
                ? messages.findBefore(conversationId, beforeSeq, PageRequest.of(0, lim))
                : messages.findAfter(conversationId, afterSeq == null ? 0 : afterSeq, PageRequest.of(0, lim));
        if (beforeSeq != null) Collections.reverse(list);
        long lastRead = participants.findByConversationIdAndUserId(conversationId, u.getId()).map(ConversationParticipant::getLastReadSeq).orElse(0L);
        List<Map<String, Object>> items = list.stream().map(m -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("seq", m.getSeq());
            v.put("senderId", m.getSenderId());
            v.put("type", m.getType().name());
            v.put("body", m.isRedacted() ? "[Nội dung đã bị ẩn]" : m.getBody());
            v.put("createdAt", m.getCreatedAt());
            return v;
        }).toList();
        return Map.of("conversationId", conversationId, "items", items, "lastSeq", c.getLastMessageSeq(), "lastReadSeq", lastRead,
                "unread", Math.max(0, c.getLastMessageSeq() - lastRead));
    }

    @Transactional
    public void markRead(AuthUser u, Long conversationId, long seq) {
        Conversation c = requireAccess(u, conversationId);
        participants.advanceRead(conversationId, u.getId(), Math.min(seq, c.getLastMessageSeq()));
    }

    /** Inbox: lọc chưa đọc / chưa trả lời / có khiếu nại / booking sắp nhận phòng. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> inbox(AuthUser u, boolean unreadOnly, boolean unanswered, boolean complaintOnly, boolean upcomingCheckin, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource ps = new MapSqlParameterSource("uid", u.getId());
        if (unreadOnly) where.append(" AND c.last_message_seq > cp.last_read_seq");
        if (unanswered) where.append(" AND (SELECT m.sender_id FROM messages m WHERE m.conversation_id = c.id AND m.seq = c.last_message_seq) <> :uid");
        if (complaintOnly) where.append(" AND EXISTS (SELECT 1 FROM complaint_tickets t WHERE t.conversation_id = c.id AND t.status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS'))");
        if (upcomingCheckin) where.append(" AND b.check_in BETWEEN CURRENT_DATE AND CURRENT_DATE + 3");
        // HOST chỉ thấy hội thoại của property mình đang sở hữu (loại cả hội thoại cũ khi đổi chủ)
        where.append(" AND (cp.role <> 'HOST' OR EXISTS (SELECT 1 FROM properties p WHERE p.id = c.property_id AND p.owner_id = :uid))");
        int sz = Math.min(Math.max(1, size), 50);
        ps.addValue("lim", sz).addValue("off", (long) Math.max(0, page) * sz);
        return jdbc.queryForList("SELECT c.id, c.property_id, c.booking_id, c.type, c.status, c.last_message_seq, cp.last_read_seq, cp.role, "
                + "(c.last_message_seq - cp.last_read_seq) AS unread, c.updated_at, b.check_in, "
                + "EXISTS (SELECT 1 FROM complaint_tickets t WHERE t.conversation_id = c.id AND t.status IN ('OPEN','ACKNOWLEDGED','IN_PROGRESS')) AS has_complaint "
                + "FROM conversations c JOIN conversation_participants cp ON cp.conversation_id = c.id AND cp.user_id = :uid "
                + "LEFT JOIN bookings b ON b.id = c.booking_id" + where + " ORDER BY c.updated_at DESC, c.id DESC LIMIT :lim OFFSET :off", ps);
    }

    // ------------------------------------------------------------------ phản ánh (complaint)
    public Map<String, Object> complain(Long customerId, Long conversationId, MessagingRequests.Complaint r) {
        Set<String> sev = Set.of("LOW", "MEDIUM", "HIGH");
        if (!sev.contains(r.severity())) throw BusinessException.validation("severity phải là LOW | MEDIUM | HIGH");
        rateLimit.check("complaint:" + customerId, 5, Duration.ofHours(1));
        TransactionTemplate tx = new TransactionTemplate(txm);
        return tx.execute(s -> {
            Conversation c = conversations.lockById(conversationId).orElseThrow(() -> BusinessException.notFound("Hội thoại"));
            if (!c.getCustomerId().equals(customerId)) throw BusinessException.notFound("Hội thoại");
            if (c.getBookingId() == null) throw BusinessException.validation("Phản ánh phải gắn với booking (BOOKING_SUPPORT)");
            Message m = appendMessage(c, customerId, MessageType.COMPLAINT, r.description(), r.clientMessageId());
            ComplaintTicket t = tickets.findByConversationIdOrderByIdDesc(conversationId).stream()
                    .filter(x -> x.getStatus() != TicketStatus.CLOSED && x.getStatus() != TicketStatus.RESOLVED).findFirst().orElse(null);
            if (t == null) {
                t = new ComplaintTicket();
                t.setConversationId(conversationId);
                t.setBookingId(c.getBookingId());
                t.setOpenedBy(customerId);
                t.setCategory(r.category());
                t.setSeverity(r.severity());
                t.setStatus(TicketStatus.OPEN);
                int hours = config.getInt("complaint.deadlineHours." + r.severity().toLowerCase());   // deadline theo cấu hình
                t.setDeadlineAt(clock.instant().plus(Duration.ofHours(hours)));
                tickets.save(t);
                outbox.publish("COMPLAINT_CREATED:" + t.getId(), "COMPLAINT_CREATED", "TICKET", t.getId(), Map.of("bookingId", c.getBookingId(), "ticketId", t.getId()));
            }
            return Map.of("ticketId", t.getId(), "status", t.getStatus().name(), "deadlineAt", String.valueOf(t.getDeadlineAt()), "messageSeq", m.getSeq());
        });
    }

    /**
     * Vòng đời: OPEN -> ACKNOWLEDGED -> IN_PROGRESS -> RESOLVED -> CLOSED.
     * RESOLVED cần ghi chú; HOST KHÔNG tự đóng (CLOSED) phản ánh - cần khách xác nhận (CONFIRM). Khách có thể từ chối kết quả / leo thang.
     */
    @Transactional
    public Map<String, Object> ticketAction(AuthUser u, Long ticketId, MessagingRequests.TicketAction a) {
        ComplaintTicket t = tickets.findById(ticketId).orElseThrow(() -> BusinessException.notFound("Phản ánh"));
        Conversation c = requireAccess(u, t.getConversationId());
        boolean customer = c.getCustomerId().equals(u.getId());
        TicketStatus from = t.getStatus();
        switch (a.action()) {
            case "ACKNOWLEDGE" -> { requireHandler(customer); expect(from, TicketStatus.OPEN); t.setStatus(TicketStatus.ACKNOWLEDGED); t.setHandlerId(t.getHandlerId() == null ? u.getId() : t.getHandlerId()); }
            case "START" -> { requireHandler(customer); expect(from, TicketStatus.ACKNOWLEDGED); t.setStatus(TicketStatus.IN_PROGRESS); }
            case "RESOLVE" -> {
                requireHandler(customer);
                if (from != TicketStatus.IN_PROGRESS && from != TicketStatus.ACKNOWLEDGED) throw BusinessException.conflict("Trạng thái không cho phép RESOLVE");
                if (a.note() == null || a.note().isBlank()) throw BusinessException.validation("RESOLVE cần ghi chú/biên bản xử lý");
                t.setStatus(TicketStatus.RESOLVED);
                t.setResolutionNote(a.note());
            }
            case "CONFIRM" -> { if (!customer) throw BusinessException.forbidden("Chỉ khách xác nhận đã giải quyết"); expect(from, TicketStatus.RESOLVED); t.setStatus(TicketStatus.CLOSED); }
            case "REJECT" -> { if (!customer) throw BusinessException.forbidden("Chỉ khách từ chối kết quả"); expect(from, TicketStatus.RESOLVED); t.setStatus(TicketStatus.IN_PROGRESS); }
            case "ESCALATE" -> {
                if (!customer) throw BusinessException.forbidden("Chỉ khách leo thang");
                if (from == TicketStatus.CLOSED) throw BusinessException.conflict("Phản ánh đã đóng");
                t.setDeadlineAt(clock.instant());   // coi như quá hạn -> SUPPORT được phép nhận xử lý
            }
            default -> throw BusinessException.validation("action không hợp lệ");
        }
        tickets.saveAndFlush(t);
        audit.record("TICKET_" + a.action(), "TICKET", ticketId, from.name(), t.getStatus().name(), a.note(), null);
        if (!customer && c.getBookingId() != null) {
            outbox.publish("COMPLAINT_REPLY:" + ticketId + ":" + t.getVersion(), "COMPLAINT_REPLY", "TICKET", ticketId, Map.of("bookingId", c.getBookingId()));
        }
        return Map.of("ticketId", ticketId, "status", t.getStatus().name());
    }

    /** SUPPORT nhận xử lý khi phản ánh quá hạn / bị leo thang; được thêm vào hội thoại với audit. */
    @Transactional
    public void assignSupport(AuthUser support, Long ticketId) {
        ComplaintTicket t = tickets.findById(ticketId).orElseThrow(() -> BusinessException.notFound("Phản ánh"));
        if (t.getStatus() == TicketStatus.CLOSED) throw BusinessException.conflict("Phản ánh đã đóng");
        if (t.getDeadlineAt() == null || t.getDeadlineAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Phản ánh chưa quá hạn hoặc chưa được leo thang");
        }
        if (participants.findByConversationIdAndUserId(t.getConversationId(), support.getId()).isEmpty()) {
            participants.save(new ConversationParticipant(t.getConversationId(), support.getId(), "SUPPORT"));
        }
        t.setHandlerId(support.getId());
        audit.record("TICKET_ASSIGN_SUPPORT", "TICKET", ticketId, null, null, null, Map.of("conversationId", t.getConversationId()));
    }

    /** Tin nhắn không bị xóa cứng; chỉ redaction có lý do + audit. */
    @Transactional
    public void redact(Long messageId, String reason) {
        Message m = messages.findById(messageId).orElseThrow(() -> BusinessException.notFound("Tin nhắn"));
        if (reason == null || reason.isBlank()) throw BusinessException.validation("Cần nêu lý do");
        m.setRedacted(true);
        audit.record("MESSAGE_REDACT", "MESSAGE", messageId, null, null, reason, Map.of("conversationId", m.getConversationId()));
    }

    private void requireHandler(boolean customer) {
        if (customer) throw BusinessException.forbidden("Khách không thực hiện thao tác xử lý");
    }

    private void expect(TicketStatus from, TicketStatus want) {
        if (from != want) throw BusinessException.conflict("Trạng thái hiện tại " + from + " không cho phép thao tác (cần " + want + ")");
    }
}
