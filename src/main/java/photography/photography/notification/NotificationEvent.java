package photography.photography.notification;

import java.time.Instant;

/**
 * The message that travels through Kafka. Keep it small, flat and backwards compatible (add optional fields only).
 *
 * @param eventId       globally unique; the consumer is idempotent on it (Kafka delivery is at-least-once)
 * @param recipient     username of the user to notify; also used as the Kafka record KEY so one user's events stay ordered
 * @param groupKey      optional; unread notifications with the same (recipient, groupKey) are collapsed into one
 *                      (e.g. 10 chat messages in one conversation = one "new messages" notification)
 */
public record NotificationEvent(
        String eventId,
        NotificationType type,
        String recipient,
        String title,
        String body,
        String link,
        String groupKey,
        Instant createdAt) {
}
