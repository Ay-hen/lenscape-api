package photography.photography.verification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A registration waiting for its e-mail code. No account exists until the code is confirmed, so an unverified (possibly someone else's) address
 * can never own an account. Removed automatically after {@code pendingRetention} by the TTL index on {@code expireAt}.
 */
@Data
@NoArgsConstructor
@Document("pending_registrations")
public class PendingRegistration {
    @Id
    private String id;
    /** Normalised (trimmed, lower-case). Unique. */
    @Indexed(unique = true)
    private String email;
    private String username;
    private String name;
    private String passwordHash;
    private String phone;
    private String city;
    private String role;
    /** HMAC of the current code; null means there is no live challenge. */
    private String otpHash;
    private Instant otpExpiresAt;
    private int failedAttempts;
    private Instant lastSentAt;
    private Instant sendWindowStart;
    private int sendCount;
    private Instant createdAt;
    /** Cleanup only (TTL index); verification never relies on it. */
    @Indexed(expireAfterSeconds = 0)
    private Instant expireAt;
}
