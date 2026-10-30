package photography.photography.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Producer side of the pipeline: business code -> Kafka topic -> {@link NotificationConsumer} -> MongoDB + WebSocket push.
 *
 * Reliability: the send is asynchronous (never blocks the HTTP request). If the broker is unreachable the event is
 * processed in-process instead, so the user still gets the notification; the consumer is idempotent on {@code eventId}.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaNotificationPublisher implements NotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final NotificationService fallback;
    private final String topic;

    public KafkaNotificationPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, NotificationService fallback,
                                      @Value("${app.kafka.notifications-topic}") String topic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.fallback = fallback;
        this.topic = topic;
    }

    @Override
    public void publish(NotificationEvent event) {
        final String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise notification event", e);
        }
        try {
            kafka.send(topic, event.recipient(), json).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Kafka send failed for event {} ({}); delivering in-process", event.eventId(), error.toString());
                    fallback.process(event);
                }
            });
        } catch (RuntimeException e) { // e.g. metadata fetch timeout is thrown synchronously
            log.warn("Kafka unavailable for event {} ({}); delivering in-process", event.eventId(), e.toString());
            fallback.process(event);
        }
    }
}
