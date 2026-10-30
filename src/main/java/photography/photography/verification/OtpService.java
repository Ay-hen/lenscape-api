package photography.photography.verification;

public interface OtpService {
    /** The data kept until the address is verified. {@code passwordHash} is already BCrypt-hashed. */
    record PendingData(String email, String username, String name, String passwordHash, String phone, String city, String role) {
    }

    /** Creates or refreshes the pending registration and e-mails a new code. */
    void startRegistration(PendingData data);

    /** Issues a fresh code for an existing pending registration (same generic outcome if none exists). */
    void resend(String email);

    /** Checks the code and atomically consumes (deletes) the pending registration. Throws when the code is wrong, expired or exhausted. */
    PendingRegistration verify(String email, String otp);

    long ttlSeconds();
}
