package photography.photography.booking;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ledger of reminders already sent. The unique id IS the lock: inserting it twice fails, so a reminder can never go out twice. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("sent_reminders")
public class SentReminder {
    /** "&lt;bookingId&gt;:24h:&lt;date&gt;T&lt;time&gt;" - a rescheduled booking gets a new key and therefore a new reminder. */
    @Id
    private String id;
    private String bookingId;
    /** Rows clean themselves up after 90 days. */
    @Indexed(expireAfterSeconds = 7_776_000)
    private Instant sentAt;
}