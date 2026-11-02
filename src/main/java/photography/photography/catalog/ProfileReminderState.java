package photography.photography.catalog;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Reminder bookkeeping, kept apart from the profile so a profile save can never overwrite it. */
@Data
@NoArgsConstructor
@Document("profile_reminders")
public class ProfileReminderState {
    @Id
    private String photographerId;
    private Instant lastRemindedAt;
    /** Reminders actually sent so far. */
    private int sent;
    private Instant snoozedUntil;
    private Instant dismissedAt;
    /** Set once the profile reached 100 % and the open reminder was closed. */
    private Instant clearedAt;
}