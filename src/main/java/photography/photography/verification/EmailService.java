package photography.photography.verification;

public interface EmailService {
    /** Throws org.springframework.mail.MailException when delivery fails. */
    void sendVerificationCode(String toEmail, String code, long validMinutes);
}
