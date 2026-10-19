package datphong.vn.controller;

import datphong.vn.dto.request.MessagingRequests;
import datphong.vn.exception.entity.CheckinRequest;
import datphong.vn.exception.entity.Message;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.CheckinRequestService;
import datphong.vn.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** UC-27/28/29: hội thoại, phản ánh, đề nghị check-in. Quyền participant được kiểm tra trong service (không chỉ ẩn nút ở frontend). */
@RestController
@RequiredArgsConstructor
public class MessagingController {

    private final ConversationService chat;
    private final CheckinRequestService checkin;

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public Map<String, Object> open(@Valid @RequestBody MessagingRequests.OpenConversation r) {
        return chat.open(CurrentUser.id(), r);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> inbox(@RequestParam(defaultValue = "false") boolean unread, @RequestParam(defaultValue = "false") boolean unanswered,
                                           @RequestParam(defaultValue = "false") boolean complaint, @RequestParam(defaultValue = "false") boolean upcomingCheckin,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return chat.inbox(CurrentUser.require(), unread, unanswered, complaint, upcomingCheckin, page, size);
    }

    @GetMapping("/conversations/{id}/messages")
    public Map<String, Object> messages(@PathVariable Long id, @RequestParam(required = false) Long afterSeq, @RequestParam(required = false) Long beforeSeq,
                                        @RequestParam(defaultValue = "50") int limit) {
        return chat.messages(CurrentUser.require(), id, afterSeq, beforeSeq, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, Object> send(@PathVariable Long id, @Valid @RequestBody MessagingRequests.SendMessage r) {
        Message m = chat.send(CurrentUser.require(), id, r);
        return Map.of("seq", m.getSeq(), "createdAt", m.getCreatedAt(), "clientMessageId", m.getClientMessageId());
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, String> read(@PathVariable Long id, @Valid @RequestBody MessagingRequests.MarkRead r) {
        chat.markRead(CurrentUser.require(), id, r.seq());
        return Map.of("status", "OK");
    }

    @PostMapping("/conversations/{id}/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public Map<String, Object> complain(@PathVariable Long id, @Valid @RequestBody MessagingRequests.Complaint r) {
        return chat.complain(CurrentUser.id(), id, r);
    }

    @PostMapping("/complaints/{ticketId}/actions")
    public Map<String, Object> ticket(@PathVariable Long ticketId, @Valid @RequestBody MessagingRequests.TicketAction a) {
        return chat.ticketAction(CurrentUser.require(), ticketId, a);
    }

    @PostMapping("/support/complaints/{ticketId}/assign")
    @PreAuthorize("hasAuthority('CONVERSATION_SUPPORT')")
    public Map<String, String> assign(@PathVariable Long ticketId) {
        chat.assignSupport(CurrentUser.require(), ticketId);
        return Map.of("status", "ASSIGNED");
    }

    @PostMapping("/support/messages/{id}/redact")
    @PreAuthorize("hasAuthority('CONVERSATION_SUPPORT')")
    public Map<String, String> redact(@PathVariable Long id, @RequestParam String reason) {
        chat.redact(id, reason);
        return Map.of("status", "REDACTED");
    }

    // ---- check-in requests (UC-29)
    private static Map<String, Object> view(CheckinRequest c) {
        return Map.of("id", c.getId(), "bookingId", c.getBookingId(), "status", c.getStatus().name(), "proposedAt", c.getProposedAt(),
                "agreedCheckinAt", String.valueOf(c.getAgreedCheckinAt()), "version", c.getVersion());
    }

    @PostMapping("/bookings/{id}/checkin-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public Map<String, Object> create(@PathVariable Long id, @Valid @RequestBody MessagingRequests.CheckinCreate r) {
        return view(checkin.create(CurrentUser.id(), id, r));
    }

    @PatchMapping("/checkin-requests/{id}/respond")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public Map<String, Object> respond(@PathVariable Long id, @Valid @RequestBody MessagingRequests.CheckinRespond r) {
        return view(checkin.respond(CurrentUser.id(), id, r));
    }

    @PostMapping("/checkin-requests/{id}/accept")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public Map<String, Object> accept(@PathVariable Long id) {
        return view(checkin.accept(CurrentUser.id(), id));
    }
}
