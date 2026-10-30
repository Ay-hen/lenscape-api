package photography.photography.notification;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user's notifications. Everything is scoped to the authenticated principal - there is no user id in the URL. */
@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationView> list(Principal me, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return service.list(me.getName(), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread(Principal me) {
        return Map.of("count", service.unreadCount(me.getName()));
    }

    @PostMapping("/{id}/read")
    public Map<String, Boolean> read(Principal me, @PathVariable String id) {
        if (!service.markRead(me.getName(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    public Map<String, Long> readAll(Principal me) {
        return Map.of("updated", service.markAllRead(me.getName()));
    }
}
