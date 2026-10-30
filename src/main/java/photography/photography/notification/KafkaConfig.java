package photography.photography.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    /** Created on start-up if missing (needs the broker's admin API; in production create topics with your infra tooling). */
    @Bean
    public NewTopic notificationsTopic(@Value("${app.kafka.notifications-topic}") String name,
                                       @Value("${app.kafka.partitions:3}") int partitions,
                                       @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }

    /** The dead-letter recoverer publishes to "<topic>.DLT" on the SAME partition number, so it needs the same partition count. */
    @Bean
    public NewTopic notificationsDeadLetterTopic(@Value("${app.kafka.notifications-topic}") String name,
                                                 @Value("${app.kafka.partitions:3}") int partitions,
                                                 @Value("${app.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(name + ".DLT").partitions(partitions).replicas(replicas).build();
    }

    /** Spring Boot applies a CommonErrorHandler bean to the auto-configured listener container factory. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
