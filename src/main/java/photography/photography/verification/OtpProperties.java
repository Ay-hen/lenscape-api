package photography.photography.verification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings for e-mail verification codes (prefix app.otp). Environment: OTP_HMAC_SECRET and the optional OTP_* overrides in application.properties. */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        @DefaultValue("PT10M") Duration ttl,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("PT1M") Duration resendCooldown,
        @DefaultValue("5") int maxSendsPerHour,
        @DefaultValue("PT24H") Duration pendingRetention,
        String hmacSecret,
        @DefaultValue("false") boolean allowEphemeralSecret) {
}
