package photography.photography.chat;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One conversation per (customer, photographer) pair. Usernames are the User primary key in this code base. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("conversations")
@CompoundIndex(name = "pair", def = "{'clientUsername': 1, 'photographerUsername': 1}", unique = true)
@CompoundIndex(name = "by_client", def = "{'clientUsername': 1, 'updatedAt': -1}")
@CompoundIndex(name = "by_photographer", def = "{'photographerUsername': 1, 'updatedAt': -1}")
public class Conversation {
    @Id
    private String id;
    private String clientUsername;
    private String clientName;
    private String photographerUsername;
    private String photographerName;
    /** Optional link to a Reservation id. */
    private String bookingId;
    private String lastMessageText;
    private Instant updatedAt;
    /** Plain counters (not a Map keyed by username) so atomic $inc works and usernames never become Mongo field names. */
    private int clientUnread;
    private int photographerUnread;

    public boolean hasParticipant(String username) {
        return username != null && (username.equals(clientUsername) || username.equals(photographerUsername));
    }

    public String otherOf(String username) {
        return username.equals(clientUsername) ? photographerUsername : clientUsername;
    }
}
