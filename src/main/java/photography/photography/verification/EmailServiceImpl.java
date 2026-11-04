package photography.photography.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the code over SMTP (spring.mail.*; Gmail needs an "app password"). With app.mail.log-only=true (profile dev) nothing is sent and the code is
 * written to the server log instead, so the flow can be tried without an SMTP account. Never enable log-only in production.
 */
@Service
public class EmailServiceImpl implements EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final boolean logOnly;
    private final String from;
    private final String brand;

    public EmailServiceImpl(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.log-only:false}") boolean logOnly,
                            @Value("${app.mail.from:}") String from, @Value("${app.brand-name:Lenscape}") String brand) {
        this.sender = sender;
        this.logOnly = logOnly;
        this.from = from;
        this.brand = brand;
    }

    @Override
    public void sendVerificationCode(String toEmail, String code, long validMinutes) {
        if (logOnly) {
            log.warn("DEV ONLY (app.mail.log-only): verification code for {} is {}", toEmail, code);
            return;
        }
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            throw new org.springframework.mail.MailSendException("No mail server configured (MAIL_USERNAME / MAIL_PASSWORD)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (!from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(toEmail);
        message.setSubject(brand + " – your verification code");
        message.setText("Your " + brand + " verification code is: " + code
                + "\n\nEnter it in the app to confirm your email address. The code expires in " + validMinutes + " minutes."
                + "\n\nIf you did not create an account, you can ignore this email.");
        mail.send(message);
    }

    @Override
    public void sendPasswordResetCode(String toEmail, String code, long validMinutes) {
        if (logOnly) {
            log.warn("DEV ONLY (app.mail.log-only): password reset code for {} is {}", toEmail, code);
            return;
        }
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            throw new org.springframework.mail.MailSendException("No mail server configured (MAIL_USERNAME / MAIL_PASSWORD)");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (!from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(toEmail);
        message.setSubject(brand + " – reset your password");
        message.setText("Your " + brand + " password reset code is: " + code
                + "\n\nEnter it on the password recovery page to continue. The code expires in " + validMinutes + " minutes and can be used once."
                + "\n\nIf you did not ask to reset your password, you can ignore this email: your password has not been changed.");
        mail.send(message);
    }
}