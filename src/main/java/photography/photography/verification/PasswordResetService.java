package photography.photography.verification;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.model.User;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;

/**
 * "Forgot password": an e-mailed 6-digit code (same strength, TTL, attempt limit and send limits as sign-up verification) proves control of the
 * address, then sets the new password and signs the account out everywhere.
 * <ul>
 *   <li>{@link #request} ALWAYS ends the same way (no error, no timing hint) so it cannot be used to find out which addresses have an account.</li>
 *   <li>Every state change is one atomic MongoDB update, so concurrent requests cannot bypass the attempt limit or reuse a code.</li>
 *   <li>Codes are bound to purpose + address ("pwd-reset:" prefix), so a sign-up code can never reset a password.</li>
 * </ul>
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration SEND_WINDOW = Duration.ofHours(1);
    private static final String PURPOSE = "pwd-reset:";

    private final PasswordResetRepo repo;
    private final UserRepo users;
    private final TokenRepo tokens;
    private final MongoTemplate mongo;
    private final EmailService email;
    private final OtpHasher hasher;
    private final OtpProperties props;
    private final PlatformClock clock;
    private final PasswordEncoder encoder;

    public PasswordResetService(PasswordResetRepo repo, UserRepo users, TokenRepo tokens, MongoTemplate mongo, EmailService email, OtpHasher hasher,
                                OtpProperties props, PlatformClock clock, PasswordEncoder encoder) {
        this.repo = repo;
        this.users = users;
        this.tokens = tokens;
        this.mongo = mongo;
        this.email = email;
        this.hasher = hasher;
        this.props = props;
        this.clock = clock;
        this.encoder = encoder;
    }

    public long ttlSeconds() {
        return props.ttl().toSeconds();
    }

    /** Sends a code if (and only if) an active account uses this address. Never reveals whether it does, and never fails because of it. */
    public void request(String emailIn) {
        String address = normalise(emailIn);
        User user = users.findByEmail(address).orElse(null);
        if (user == null || user.isSuspended()) {
            return;
        }
        String otp = hasher.generateOtp();
        String otpHash = hasher.hash(PURPOSE + address, otp);
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
        try {
            email.sendPasswordResetCode(address, otp, Math.max(1, props.ttl().toMinutes()));
        } catch (MailException ex) {
            log.warn("Password reset e-mail delivery failed ({})", ex.getClass().getSimpleName()); // never log the code or the address
            mongo.updateFirst(Query.query(Criteria.where("email").is(address).and("otpHash").is(otpHash)),
                    new Update().set("otpHash", null).set("lastSentAt", Instant.EPOCH), PasswordReset.class);
        }
    }

    /** Checks the code (one attempt is reserved atomically), consumes it, stores the new password and revokes every session of the account. */
    public void reset(String emailIn, String otp, String newPassword) {
        String address = normalise(emailIn);
        Instant now = clock.instant();
        Query attemptable = Query.query(Criteria.where("email").is(address).and("otpHash").ne(null).and("otpExpiresAt").gt(now)
                .and("failedAttempts").lt(props.maxFailedAttempts()));
        PasswordReset challenge = mongo.findAndModify(attemptable, new Update().inc("failedAttempts", 1), FindAndModifyOptions.options().returnNew(true), PasswordReset.class);
        if (challenge == null) {
            throw invalidCode();
        }
        if (!hasher.matches(challenge.getOtpHash(), PURPOSE + address, otp)) {
            if (challenge.getFailedAttempts() >= props.maxFailedAttempts()) {
                mongo.updateFirst(Query.query(Criteria.where("id").is(challenge.getId())), new Update().set("otpHash", null), PasswordReset.class);
                throw ApiException.rateLimited("Too many incorrect attempts. Request a new code.", props.resendCooldown().toSeconds());
            }
            throw invalidCode();
        }
        PasswordReset consumed = mongo.findAndRemove(Query.query(Criteria.where("id").is(challenge.getId()).and("otpHash").is(challenge.getOtpHash())), PasswordReset.class);
        if (consumed == null) {
            throw invalidCode(); // a concurrent request used this code first
        }
        User user = users.findByEmail(address).orElseThrow(PasswordResetService::invalidCode);
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
        p.setOtpExpiresAt(now.plus(props.ttl()));
        p.setFailedAttempts(0);
        p.setLastSentAt(now);
        p.setSendWindowStart(now);
        p.setSendCount(1);
        p.setExpireAt(now.plus(props.pendingRetention()));
        return p;
    }

    /** Atomically takes a send slot (cooldown + hourly cap) and replaces the code in the same update (the previous code stops working). */
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
        return new Update().set("otpHash", otpHash).set("otpExpiresAt", now.plus(props.ttl())).set("failedAttempts", 0).set("lastSentAt", now)
                .set("expireAt", now.plus(props.pendingRetention()));
    }

    private static String normalise(String e) {
        return e == null ? "" : e.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidCode() {
        return new ApiException(400, "INVALID_CODE", "Invalid or expired code", null);
    }
}