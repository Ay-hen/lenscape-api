package photography.photography.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Consumer side. Failure policy (see KafkaConfig): 3 retries, 1 s apart, then the record goes to "&lt;topic&gt;.DLT".
 * Unparseable payloads skip the retries because they can never succeed.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConsumer {
    private final ObjectMapper mapper;
    private final NotificationService service;

    public NotificationConsumer(ObjectMapper mapper, NotificationService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @KafkaListener(topics = "${app.kafka.notifications-topic}", groupId = "${app.kafka.consumer-group}")
    public void onEvent(ConsumerRecord<String, String> record) {
        final NotificationEvent event;
        try {
            event = mapper.readValue(record.value(), NotificationEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed notification event at offset " + record.offset(), e);
        }
        service.process(event);
    }
}
