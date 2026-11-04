package photography.photography.verification;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.model.User;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;

/**
 * "Forgot password" in three stages. The person must pass every stage in order; the password can only be changed with the authorisation
 * produced by the previous one.
 * <ol>
 *   <li>{@link #request}: an e-mailed 6-digit code, only when an active account uses the address.</li>
 *   <li>{@link #verifyCode}: the code is checked (attempt-limited) and consumed, and a random single-use <em>reset token</em> is returned.</li>
 *   <li>{@link #reset}: the reset token (not the code) authorises the new password; it is consumed, and every session of the account is revoked.</li>
 * </ol>
 * Security properties:
 * <ul>
 *   <li>{@link #request} ALWAYS ends the same way, so it cannot be used to find out which addresses have an account. The e-mail is handed to a
 *       background thread so the response time does not depend on whether a message was sent either.</li>
 *   <li>Codes and reset tokens are stored only as HMACs, are bound to purpose + address, and every state change is one atomic MongoDB update, so
 *       concurrent requests cannot bypass the attempt limit, the resend cooldown or the hourly cap, nor use a code or token twice.
 *       A sign-up code can never be used here (different purpose prefix), and neither can a code of another address.</li>
 *   <li>Requesting a new code invalidates the previous code AND any reset token that was already issued.</li>
 *   <li>Neither codes, tokens, nor addresses are ever logged.</li>
 * </ul>
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration SEND_WINDOW = Duration.ofHours(1);
    /** HMAC domain separation: a value hashed for one purpose can never validate for another. */
    private static final String CODE_PURPOSE = "pwd-reset:";
    private static final String TOKEN_PURPOSE = "pwd-reset-token:";
    private static final int TOKEN_BYTES = 32;

    /** What a successful code check hands back: the one-time authorisation for {@link #reset} and its lifetime. */
    public record ResetGrant(String resetToken, long expiresInSeconds) {
    }

    private final PasswordResetRepo repo;
    private final UserRepo users;
    private final TokenRepo tokens;
    private final MongoTemplate mongo;
    private final EmailService email;
    private final OtpHasher hasher;
    private final PasswordResetProperties props;
    private final PlatformClock clock;
    private final PasswordEncoder encoder;
    private final Executor mailer;
    private final ThreadPoolExecutor ownedPool;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PasswordResetService(PasswordResetRepo repo, UserRepo users, TokenRepo tokens, MongoTemplate mongo, EmailService email, OtpHasher hasher,
                                PasswordResetProperties props, PlatformClock clock, PasswordEncoder encoder) {
        this(repo, users, tokens, mongo, email, hasher, props, clock, encoder, null);
    }

    /** {@code mailer == null} creates the default small background pool; tests pass a direct executor to make delivery synchronous. */
    PasswordResetService(PasswordResetRepo repo, UserRepo users, TokenRepo tokens, MongoTemplate mongo, EmailService email, OtpHasher hasher,
                         PasswordResetProperties props, PlatformClock clock, PasswordEncoder encoder, Executor mailer) {
        this.repo = repo;
        this.users = users;
        this.tokens = tokens;
        this.mongo = mongo;
        this.email = email;
        this.hasher = hasher;
        this.props = props;
        this.clock = clock;
        this.encoder = encoder;
        if (mailer == null) {
            this.ownedPool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100), r -> {
                Thread t = new Thread(r, "password-reset-mail");
                t.setDaemon(true);
                return t;
            });
            this.mailer = ownedPool;
        } else {
            this.ownedPool = null;
            this.mailer = mailer;
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            ownedPool.shutdown();
        }
    }

    public long otpTtlSeconds() {
        return props.otpTtl().toSeconds();
    }

    public long resendCooldownSeconds() {
        return props.resendCooldown().toSeconds();
    }

    // ------------------------------------------------------------------ stage 1: request (and resend) a code

    /** Sends a code if (and only if) an active account uses this address. Never reveals whether it does, and never fails because of it. */
    public void request(String emailIn) {
        String address = normalise(emailIn);
        User user = users.findByEmail(address).orElse(null);
        if (user == null || user.isSuspended()) {
            return;
        }
        String otp = hasher.generateOtp();
        String otpHash = hasher.hash(CODE_PURPOSE + address, otp);
        Instant now = clock.instant();
        boolean issued;
        if (repo.findByEmail(address).isPresent()) {
            issued = claimSendSlot(address, now, otpHash);
        } else {
            try {
                repo.insert(fresh(address, otpHash, now));
                issued = true;
            } catch (DuplicateKeyException race) {
                issued = claimSendSlot(address, now, otpHash);
            }
        }
        if (!issued) {
            return; // cooldown / hourly cap: silently ignored (an error here would reveal that the account exists)
        }
        long minutes = Math.max(1, props.otpTtl().toMinutes());
        try {
            mailer.execute(() -> deliver(address, otp, otpHash, minutes));
        } catch (RejectedExecutionException busy) {
            log.warn("Password reset e-mail queue is full");
            releaseUndelivered(address, otpHash);
        }
    }

    private void deliver(String address, String otp, String otpHash, long minutes) {
        try {
            email.sendPasswordResetCode(address, otp, minutes);
        } catch (MailException ex) {
            log.warn("Password reset e-mail delivery failed ({})", ex.getClass().getSimpleName()); // never log the code or the address
            releaseUndelivered(address, otpHash);
        } catch (RuntimeException ex) {
            log.warn("Password reset e-mail delivery crashed ({})", ex.getClass().getSimpleName());
            releaseUndelivered(address, otpHash);
        }
    }

    /** Drops a code that never reached the person and frees the cooldown so that they can ask again immediately. */
    private void releaseUndelivered(String address, String otpHash) {
        mongo.updateFirst(Query.query(Criteria.where("email").is(address).and("otpHash").is(otpHash)),
                new Update().set("otpHash", null).set("lastSentAt", Instant.EPOCH), PasswordReset.class);
    }

    // ------------------------------------------------------------------ stage 2: verify the code, receive a reset token

    /**
     * Checks the code. One attempt is reserved atomically BEFORE the comparison, so parallel guesses cannot exceed the limit. A correct code is
     * consumed in the same atomic update that stores the hash of a new random reset token; the token itself is returned once and never stored.
     * Wrong, expired, unknown-address and already-used codes all fail with the same {@code INVALID_CODE} answer.
     */
    public ResetGrant verifyCode(String emailIn, String otp) {
        String address = normalise(emailIn);
        Instant now = clock.instant();
        Query attemptable = Query.query(Criteria.where("email").is(address).and("otpHash").ne(null).and("otpExpiresAt").gt(now)
                .and("failedAttempts").lt(props.maxFailedAttempts()));
        PasswordReset challenge = mongo.findAndModify(attemptable, new Update().inc("failedAttempts", 1), FindAndModifyOptions.options().returnNew(true),
                PasswordReset.class);
        if (challenge == null) {
            throw invalidCode();
        }
        if (!hasher.matches(challenge.getOtpHash(), CODE_PURPOSE + address, otp)) {
            if (challenge.getFailedAttempts() >= props.maxFailedAttempts()) {
                mongo.updateFirst(Query.query(Criteria.where("id").is(challenge.getId())), new Update().set("otpHash", null), PasswordReset.class);
                throw ApiException.rateLimited("Too many incorrect attempts. Request a new code.", props.resendCooldown().toSeconds());
            }
            throw invalidCode();
        }
        String token = newResetToken();
        Query consumeCode = Query.query(Criteria.where("id").is(challenge.getId()).and("otpHash").is(challenge.getOtpHash()));
        Update grant = new Update().set("otpHash", null).set("failedAttempts", 0).set("resetTokenHash", hasher.hash(TOKEN_PURPOSE + address, token))
                .set("resetTokenExpiresAt", now.plus(props.resetTokenTtl()));
        if (mongo.findAndModify(consumeCode, grant, PasswordReset.class) == null) {
            throw invalidCode(); // a concurrent request used this code first
        }
        return new ResetGrant(token, props.resetTokenTtl().toSeconds());
    }

    // ------------------------------------------------------------------ stage 3: set the new password

    /**
     * Sets the new password. Only a live reset token issued by {@link #verifyCode} for THIS address is accepted; it is consumed atomically (a
     * second use fails), the password is stored with the application's PasswordEncoder, and every session of the account is revoked.
     */
    public void reset(String emailIn, String resetToken, String newPassword) {
        String address = normalise(emailIn);
        Instant now = clock.instant();
        Query live = Query.query(Criteria.where("email").is(address).and("resetTokenHash").is(hasher.hash(TOKEN_PURPOSE + address, resetToken))
                .and("resetTokenExpiresAt").gt(now));
        Update consume = new Update().set("resetTokenHash", null).set("resetTokenExpiresAt", null).set("otpHash", null);
        if (mongo.findAndModify(live, consume, PasswordReset.class) == null) {
            throw invalidResetToken();
        }
        User user = users.findByEmail(address).orElseThrow(PasswordResetService::invalidResetToken);
        if (user.isSuspended()) {
            throw ApiException.forbidden("This account is suspended. Contact support.");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getUsername())), new Update().set("password", encoder.encode(newPassword)), User.class);
        tokens.deleteByUsername(user.getUsername()); // whoever knew the old password (or holds an old token) is signed out
    }

    // ------------------------------------------------------------------ helpers

    private PasswordReset fresh(String address, String otpHash, Instant now) {
        PasswordReset p = new PasswordReset();
        p.setEmail(address);
        p.setOtpHash(otpHash);
        p.setOtpExpiresAt(now.plus(props.otpTtl()));
        p.setFailedAttempts(0);
        p.setLastSentAt(now);
        p.setSendWindowStart(now);
        p.setSendCount(1);
        p.setExpireAt(now.plus(props.retention()));
        return p;
    }

    /** Atomically takes a send slot (cooldown + hourly cap) and replaces the code in the same update (the previous code and any reset token stop working). */
    private boolean claimSendSlot(String address, Instant now, String otpHash) {
        Instant cooldownEdge = now.minus(props.resendCooldown());
        Instant windowEdge = now.minus(SEND_WINDOW);
        Query windowExpired = Query.query(Criteria.where("email").is(address).and("lastSentAt").lte(cooldownEdge).and("sendWindowStart").lte(windowEdge));
        if (mongo.findAndModify(windowExpired, challenge(now, otpHash).set("sendWindowStart", now).set("sendCount", 1), PasswordReset.class) != null) {
            return true;
        }
        Query windowActive = Query.query(Criteria.where("email").is(address).and("lastSentAt").lte(cooldownEdge).and("sendWindowStart").gt(windowEdge)
                .and("sendCount").lt(props.maxSendsPerHour()));
        return mongo.findAndModify(windowActive, challenge(now, otpHash).inc("sendCount", 1), PasswordReset.class) != null;
    }

    private Update challenge(Instant now, String otpHash) {
        return new Update().set("otpHash", otpHash).set("otpExpiresAt", now.plus(props.otpTtl())).set("failedAttempts", 0).set("lastSentAt", now)
                .set("resetTokenHash", null).set("resetTokenExpiresAt", null).set("expireAt", now.plus(props.retention()));
    }

    /** 256 random bits, URL-safe. */
    private String newResetToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalise(String e) {
        return e == null ? "" : e.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCode() {
        return new ApiException(400, "INVALID_CODE", "Invalid or expired code", null);
    }

    private static ApiException invalidResetToken() {
        return new ApiException(400, "INVALID_RESET_TOKEN", "This password reset session has expired or was already used. Please start again.", null);
    }
}