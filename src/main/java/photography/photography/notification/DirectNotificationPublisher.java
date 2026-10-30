package photography.photography.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** `app.kafka.enabled=false`: skip the broker and process the event immediately in this JVM (dev / tests). */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false")
public class DirectNotificationPublisher implements NotificationPublisher {
    private final NotificationService service;

    public DirectNotificationPublisher(NotificationService service) {
        this.service = service;
    }

    @Override
    public void publish(NotificationEvent event) {
        service.process(event);
    }
}
