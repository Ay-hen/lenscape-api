package photography.photography.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class KafkaNotificationPublisherTest {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    NotificationService fallback = mock(NotificationService.class);
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    NotificationEvent event = new NotificationEvent("e1", NotificationType.BOOKING, "bob", "t", "b", "/l", null, Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void sendsToTopicKeyedByRecipient() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        new KafkaNotificationPublisher(kafka, mapper, fallback, "topic").publish(event);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("topic"), org.mockito.ArgumentMatchers.eq("bob"), anyString());
        verify(fallback, never()).process(any());
    }

    @Test
    void fallsBackInProcessWhenBrokerFails() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        new KafkaNotificationPublisher(kafka, mapper, fallback, "topic").publish(event);
        verify(fallback).process(event);
    }

    @Test
    void fallsBackWhenSendThrowsSynchronously() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("timeout"));
        new KafkaNotificationPublisher(kafka, mapper, fallback, "topic").publish(event);
        verify(fallback).process(event);
    }
}
