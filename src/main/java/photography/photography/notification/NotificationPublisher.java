package photography.photography.notification;

/** Entry point used by the rest of the application. Two implementations: Kafka, or in-process (when Kafka is disabled). */
public interface NotificationPublisher {
    void publish(NotificationEvent event);
}
