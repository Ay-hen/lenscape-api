package photography.photography.notification;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class NotificationServiceTest {
    NotificationRepo repo = mock(NotificationRepo.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repo, mongo, ws);
    }

    private NotificationEvent event(String id, String group) {
        return new NotificationEvent(id, NotificationType.MESSAGE, "alice", "t", "b", "/x", group, Instant.now());
    }

    @Test
    void insertsAndPushesNewNotification() {
        when(repo.insert(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        service.process(event("e1", null));
        verify(ws).convertAndSendToUser(eq("alice"), eq("/queue/notifications"), any(NotificationView.class));
    }

    @Test
    void duplicateDeliveryIsIgnored() {
        when(repo.insert(any(Notification.class))).thenThrow(new DuplicateKeyException("dup"));
        service.process(event("e1", null));
        verify(ws, never()).convertAndSendToUser(any(), any(), any(Object.class));
    }

    @Test
    void collapsesIntoUnreadNotificationOfSameGroup() {
        Notification existing = Notification.builder().id("e0").userId("alice").type(NotificationType.MESSAGE).groupKey("g").read(false).build();
        when(repo.findFirstByUserIdAndGroupKeyAndReadFalse("alice", "g")).thenReturn(Optional.of(existing));
        service.process(event("e2", "g"));
        verify(repo).save(existing);
        verify(repo, never()).insert(any(Notification.class));
    }

    @Test
    void rejectsIncompleteEvents() {
        assertThrows(IllegalArgumentException.class, () -> service.process(new NotificationEvent(null, null, null, "t", "b", null, null, Instant.now())));
    }
}
