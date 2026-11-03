package photography.photography.verification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Indexed(expireAfterSeconds = 0)
    private Instant expireAt;       
}