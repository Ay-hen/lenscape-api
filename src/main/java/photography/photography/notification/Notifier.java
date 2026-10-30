package photography.photography.notification;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Small facade so business code does not know about Kafka. Never throws: a notification problem must not fail a booking or a chat message. */
@Component
public class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private final NotificationPublisher publisher;

    public Notifier(NotificationPublisher publisher) {
        this.publisher = publisher;
    }

    public void notify(String recipient, NotificationType type, String title, String body, String link, String groupKey) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        try {
            publisher.publish(new NotificationEvent(UUID.randomUUID().toString(), type, recipient, truncate(title, 120), truncate(body, 400), link, groupKey, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Could not publish notification for {}: {}", recipient, e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
