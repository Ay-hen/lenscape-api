package photography.photography.verification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the "forgot password" flow (prefix app.password-reset). They are separate from {@link OtpProperties} (sign-up codes) because a
 * recovery code protects an existing account and therefore lives shorter. The HMAC secret is shared with sign-up (app.otp.hmac-secret).
 *
 * @param otpTtl            how long the e-mailed 6-digit code can be used
 * @param resetTokenTtl     how long the single-use reset authorisation (issued after the code is verified) can be used to set the new password
 * @param maxFailedAttempts wrong guesses allowed per code; afterwards the code is dead and a new one must be requested
 * @param resendCooldown    minimum time between two codes for the same address
 * @param maxSendsPerHour   codes per address per hour
 * @param retention         how long the bookkeeping record (counters only, never a code) is kept; it must be at least one hour
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        @DefaultValue("PT5M") Duration otpTtl,
        @DefaultValue("PT10M") Duration resetTokenTtl,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("PT1M") Duration resendCooldown,
        @DefaultValue("5") int maxSendsPerHour,
        @DefaultValue("PT24H") Duration retention) {
}
