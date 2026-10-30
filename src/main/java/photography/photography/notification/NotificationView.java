package photography.photography.notification;

import java.time.Instant;

/** JSON contract used by REST and by the WebSocket push (`/user/queue/notifications`); mirrors the front-end `AppNotification`. */
public record NotificationView(String id, String userId, String type, String title, String body, Instant createdAt, boolean read, String link) {
    public static NotificationView of(Notification n) {
        return new NotificationView(n.getId(), n.getUserId(), n.getType().wire(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.isRead(), n.getLink());
    }
}
