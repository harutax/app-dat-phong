package datphong.vn.controller;

import datphong.vn.exception.entity.Notification;
import datphong.vn.security.CurrentUser;
import datphong.vn.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<Notification> p = service.list(CurrentUser.id(), page, size);
        return Map.of("items", p.map(n -> Map.of("id", n.getId(), "type", n.getType(), "title", n.getTitle(), "body", String.valueOf(n.getBody()),
                "objectType", String.valueOf(n.getObjectType()), "objectId", String.valueOf(n.getObjectId()), "read", n.getReadAt() != null,
                "createdAt", n.getCreatedAt())).getContent(), "total", p.getTotalElements(), "unread", service.unread(CurrentUser.id()));
    }

    @PatchMapping("/{id}/read")
    public Map<String, String> read(@PathVariable Long id) {
        service.markRead(CurrentUser.id(), id);
        return Map.of("status", "OK");
    }
}
