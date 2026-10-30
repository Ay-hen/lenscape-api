package photography.photography.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OtpHasherTest {
    static OtpProperties props(String secret, boolean ephemeral) {
        return new OtpProperties(Duration.ofMinutes(10), 5, Duration.ofMinutes(1), 5, Duration.ofHours(24), secret, ephemeral);
    }

    final OtpHasher hasher = new OtpHasher(props("0123456789abcdef0123456789abcdef-secret", false));

    @Test
    void codesAreSixDigitsAndVary() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String c = hasher.generateOtp();
            assertTrue(c.matches("\\d{6}"), c);
            seen.add(c);
        }
        assertTrue(seen.size() > 150, "codes must not repeat predictably");
    }

    @Test
    void hashIsDeterministicAndBoundToTheEmail() {
        assertEquals(hasher.hash("a@x.com", "123456"), hasher.hash("a@x.com", "123456"));
        assertNotEquals(hasher.hash("a@x.com", "123456"), hasher.hash("b@x.com", "123456"));
        assertNotEquals(hasher.hash("a@x.com", "123456"), hasher.hash("a@x.com", "123457"));
        assertFalse(hasher.hash("a@x.com", "123456").contains("123456"));
    }

    @Test
    void matchesOnlyTheRightCodeForTheRightEmail() {
        String h = hasher.hash("a@x.com", "654321");
        assertTrue(hasher.matches(h, "a@x.com", "654321"));
        assertFalse(hasher.matches(h, "a@x.com", "000000"));
        assertFalse(hasher.matches(h, "b@x.com", "654321"));
        assertFalse(hasher.matches(null, "a@x.com", "654321"), "a consumed/invalidated challenge never matches");
        assertFalse(hasher.matches(h, "a@x.com", null));
    }

    @Test
    void differentSecretsGiveDifferentHashes() {
        OtpHasher other = new OtpHasher(props("another-secret-another-secret-another-secret", false));
        assertNotEquals(hasher.hash("a@x.com", "111111"), other.hash("a@x.com", "111111"));
    }

    @Test
    void secretIsMandatoryAndMustBeStrongExceptInDev() {
        assertThrows(IllegalStateException.class, () -> new OtpHasher(props(null, false)));
        assertThrows(IllegalStateException.class, () -> new OtpHasher(props("", false)));
        assertThrows(IllegalStateException.class, () -> new OtpHasher(props("too-short", false)));
        assertThrows(IllegalStateException.class, () -> new OtpHasher(props("too-short", true)), "a weak explicit key is refused even in dev");
        new OtpHasher(props(null, true)); // dev: random temporary key
    }
}
