package photography.photography.verification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates 6-digit codes and stores only HMAC-SHA256(secret, email + ":" + code). A leaked database therefore does not reveal valid codes,
 * and a code is only valid for the e-mail address it was issued to.
 */
@Component
public class OtpHasher {
    private static final Logger log = LoggerFactory.getLogger(OtpHasher.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpHasher(OtpProperties properties) {
        String secret = properties.hmacSecret();
        if (secret == null || secret.isBlank()) {
            if (!properties.allowEphemeralSecret()) {
                throw new IllegalStateException("OTP_HMAC_SECRET is not set. Generate one with `openssl rand -base64 48` (see .env.example). "
                        + "For local development you may start with SPRING_PROFILES_ACTIVE=dev instead.");
            }
            byte[] random = new byte[48];
            secureRandom.nextBytes(random);
            secret = java.util.Base64.getEncoder().encodeToString(random);
            log.warn("OTP_HMAC_SECRET is not set: using a temporary random key (profile dev). Pending verification codes become invalid on restart.");
        } else if (secret.length() < 32) {
            throw new IllegalStateException("OTP_HMAC_SECRET must be at least 32 characters (`openssl rand -base64 48`).");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String generateOtp() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    public String hash(String email, String otp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((email + ":" + otp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC algorithm unavailable", ex);
        }
    }

    /** Constant-time comparison. */
    public boolean matches(String expectedHash, String email, String otp) {
        if (expectedHash == null || otp == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8), hash(email, otp).getBytes(StandardCharsets.UTF_8));
    }
}
