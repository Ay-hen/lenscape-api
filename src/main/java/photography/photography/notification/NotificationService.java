package photography.photography.notification;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists notifications and pushes them to the user's open WebSocket sessions.
 * {@link #process} is the single place both the Kafka consumer and the in-process fallback call, and it is IDEMPOTENT.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final String USER_QUEUE = "/queue/notifications";

    private final NotificationRepo repo;
    private final MongoTemplate mongo;
    private final SimpMessagingTemplate ws;

    public NotificationService(NotificationRepo repo, MongoTemplate mongo, SimpMessagingTemplate ws) {
        this.repo = repo;
        this.mongo = mongo;
        this.ws = ws;
    }

    public void process(NotificationEvent e) {
        if (e == null || e.eventId() == null || e.recipient() == null || e.type() == null) {
            throw new IllegalArgumentException("Incomplete notification event");
        }
        Notification saved;
        Optional<Notification> existing = e.groupKey() == null ? Optional.empty()
                : repo.findFirstByUserIdAndGroupKeyAndReadFalse(e.recipient(), e.groupKey());
        if (existing.isPresent()) {
            // collapse into the unread notification of the same group (e.g. one conversation)
            saved = existing.get();
            if (e.eventId().equals(saved.getId())) {
                return; // redelivery of the event that created it
            }
            saved.setTitle(e.title());
            saved.setBody(e.body());
            saved.setLink(e.link());
            saved.setCreatedAt(e.createdAt());
            repo.save(saved);
        } else {
            try {
                saved = repo.insert(Notification.builder()
                        .id(e.eventId()).userId(e.recipient()).type(e.type()).title(e.title()).body(e.body())
                        .link(e.link()).groupKey(e.groupKey()).read(false).createdAt(e.createdAt()).build());
            } catch (DuplicateKeyException duplicate) {
                log.debug("Notification {} already processed", e.eventId());
                return; // at-least-once delivery: second copy is ignored
            }
        }
        push(saved);
    }

    private void push(Notification n) {
        try {
            ws.convertAndSendToUser(n.getUserId(), USER_QUEUE, NotificationView.of(n));
        } catch (RuntimeException ex) { // the user simply has no live session; they will see it on next load
            log.debug("Push skipped for {}: {}", n.getUserId(), ex.toString());
        }
    }

    public List<NotificationView> list(String user, int page, int size) {
        int s = Math.max(1, Math.min(size, 50));
        return repo.findByUserIdOrderByCreatedAtDesc(user, PageRequest.of(Math.max(0, page), s)).stream().map(NotificationView::of).toList();
    }

    public long unreadCount(String user) {
        return repo.countByUserIdAndReadFalse(user);
    }

    /** @return false when the notification does not exist or belongs to someone else (callers answer 404 either way). */
    public boolean markRead(String user, String id) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("userId").is(user)), Update.update("read", true), Notification.class).getMatchedCount() > 0;
    }

    public boolean delete(String user, String id) {
        return mongo.remove(Query.query(Criteria.where("_id").is(id).and("userId").is(user)), Notification.class).getDeletedCount() > 0;
    }

    /** @param onlyRead true = remove only notifications already read */
    public long clear(String user, boolean onlyRead) {
        Criteria c = Criteria.where("userId").is(user);
        if (onlyRead) {
            c = c.and("read").is(true);
        }
        return mongo.remove(Query.query(c), Notification.class).getDeletedCount();
    }

    public long markAllRead(String user) {
        return mongo.updateMulti(Query.query(Criteria.where("userId").is(user).and("read").is(false)), Update.update("read", true), Notification.class).getModifiedCount();
    }
}