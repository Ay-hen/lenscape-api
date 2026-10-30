package photography.photography.notification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("notifications")
@CompoundIndex(name = "by_user_time", def = "{'userId': 1, 'createdAt': -1}")
@CompoundIndex(name = "by_user_group", def = "{'userId': 1, 'groupKey': 1, 'read': 1}")
public class Notification {
    /** Equals the Kafka event id, which makes redelivery a harmless duplicate-key. */
    @Id
    private String id;
    private String userId;
    private NotificationType type;
    private String title;
    private String body;
    private String link;
    private String groupKey;
    private boolean read;
    private Instant createdAt;
}
