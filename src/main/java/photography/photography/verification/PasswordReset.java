package photography.photography.verification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One password-recovery attempt per e-mail address. Nothing secret is stored in clear: {@code otpHash} and {@code resetTokenHash} are HMACs
 * (see {@link OtpHasher}), so a leaked database reveals neither the code nor the reset authorisation.
 * Lifecycle: code issued ({@code otpHash} set) -> code verified ({@code otpHash} cleared, {@code resetTokenHash} set) -> password changed
 * ({@code resetTokenHash} cleared). The record itself is removed by the TTL index on {@code expireAt}.
 */
@Data
@NoArgsConstructor
@Document("password_resets")
public class PasswordReset {
    @Id
    private String id;
    @Indexed(unique = true)
    private String email;
    private String otpHash;
    private Instant otpExpiresAt;
    private int failedAttempts;
    private Instant lastSentAt;
    private Instant sendWindowStart;
    private int sendCount;
    /** HMAC of the single-use reset token handed out after the code was verified; null means none is live. */
    private String resetTokenHash;
    private Instant resetTokenExpiresAt;
    /** Cleanup only (TTL index); verification never relies on it. */
    @Indexed(expireAfterSeconds = 0)
    private Instant expireAt;
}