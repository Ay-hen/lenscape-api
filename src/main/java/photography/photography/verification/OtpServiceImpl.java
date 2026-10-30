package photography.photography.verification;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;

/**
 * Code issuing and checking. Every state change is a single atomic MongoDB update, so concurrent requests cannot bypass the attempt limit,
 * the resend cooldown or the hourly cap, and a code can be consumed exactly once. (Design adapted from the Nexus backend.)
 */
@Service
public class OtpServiceImpl implements OtpService {
    private static final Logger log = LoggerFactory.getLogger(OtpServiceImpl.class);
    private static final Duration SEND_WINDOW = Duration.ofHours(1);

    private final PendingRegistrationRepo pendingRepository;
    private final MongoTemplate mongo;
    private final EmailService emailService;
    private final OtpHasher hasher;
    private final OtpProperties props;
    private final PlatformClock clock;

    public OtpServiceImpl(PendingRegistrationRepo pendingRepository, MongoTemplate mongo, EmailService emailService, OtpHasher hasher, OtpProperties props, PlatformClock clock) {
        this.pendingRepository = pendingRepository;
        this.mongo = mongo;
        this.emailService = emailService;
        this.hasher = hasher;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public long ttlSeconds() {
        return props.ttl().toSeconds();
    }

    @Override
    public void startRegistration(PendingData d) {
        String otp = hasher.generateOtp();
        String otpHash = hasher.hash(d.email(), otp);
        Instant now = clock.instant();

        boolean issued;
        if (pendingRepository.existsByEmail(d.email())) {
            issued = claimSendSlot(d.email(), now, otpHash, d);
        } else {
            try {
                pendingRepository.insert(newPending(d, otpHash, now));
                issued = true;
            } catch (DuplicateKeyException race) { // another request created it between our check and insert
                issued = claimSendSlot(d.email(), now, otpHash, d);
            }
        }
        if (!issued) {
            throw tooManyRequests(d.email(), now);
        }
        deliver(d.email(), otp, otpHash);
    }

    @Override
    public void resend(String email) {
        String otp = hasher.generateOtp();
        String otpHash = hasher.hash(email, otp);
        Instant now = clock.instant();
        if (!claimSendSlot(email, now, otpHash, null)) {
            if (pendingRepository.existsByEmail(email)) {
                throw tooManyRequests(email, now);
            }
            return; // nothing pending: same outcome as success, so this endpoint does not reveal which addresses are registering
        }
        deliver(email, otp, otpHash);
    }

    @Override
    public PendingRegistration verify(String email, String otp) {
        Instant now = clock.instant();
        // Reserve ONE attempt atomically. The code must be live, strictly unexpired, and below the attempt limit.
        Query attemptable = Query.query(Criteria.where("email").is(email).and("otpHash").ne(null).and("otpExpiresAt").gt(now)
                .and("failedAttempts").lt(props.maxFailedAttempts()));
        PendingRegistration pending = mongo.findAndModify(attemptable, new Update().inc("failedAttempts", 1), FindAndModifyOptions.options().returnNew(true),
                PendingRegistration.class);
        if (pending == null) {
            throw invalidCode();
        }
        if (!hasher.matches(pending.getOtpHash(), email, otp)) {
            if (pending.getFailedAttempts() >= props.maxFailedAttempts()) {
                mongo.updateFirst(Query.query(Criteria.where("id").is(pending.getId())), new Update().set("otpHash", null), PendingRegistration.class);
                throw ApiException.rateLimited("Too many incorrect attempts. Request a new verification code.", props.resendCooldown().toSeconds());
            }
            throw invalidCode();
        }
        // Consume: only one concurrent request can delete the record.
        PendingRegistration consumed = mongo.findAndRemove(Query.query(Criteria.where("id").is(pending.getId()).and("otpHash").is(pending.getOtpHash())),
                PendingRegistration.class);
        if (consumed == null) {
            throw invalidCode();
        }
        return consumed;
    }

    // ------------------------------------------------------------------ helpers

    private PendingRegistration newPending(PendingData d, String otpHash, Instant now) {
        PendingRegistration p = new PendingRegistration();
        p.setEmail(d.email());
        p.setUsername(d.username());
        p.setName(d.name());
        p.setPasswordHash(d.passwordHash());
        p.setPhone(d.phone());
        p.setCity(d.city());
        p.setRole(d.role());
        p.setOtpHash(otpHash);
        p.setOtpExpiresAt(now.plus(props.ttl()));
        p.setFailedAttempts(0);
        p.setLastSentAt(now);
        p.setSendWindowStart(now);
        p.setSendCount(1);
        p.setCreatedAt(now);
        p.setExpireAt(now.plus(props.pendingRetention()));
        return p;
    }

    /**
     * Atomically takes a send slot (cooldown + hourly cap) and, in the same update, replaces the code (which invalidates the previous one).
     * False when nothing matched: no pending record, still in cooldown, or hourly cap reached.
     */
    private boolean claimSendSlot(String email, Instant now, String otpHash, PendingData data) {
        Instant cooldownEdge = now.minus(props.resendCooldown());
        Instant windowEdge = now.minus(SEND_WINDOW);

        Query windowExpired = Query.query(Criteria.where("email").is(email).and("lastSentAt").lte(cooldownEdge).and("sendWindowStart").lte(windowEdge));
        Update restart = challengeUpdate(now, otpHash, data).set("sendWindowStart", now).set("sendCount", 1);
        if (mongo.findAndModify(windowExpired, restart, PendingRegistration.class) != null) {
            return true;
        }
        Query windowActive = Query.query(Criteria.where("email").is(email).and("lastSentAt").lte(cooldownEdge).and("sendWindowStart").gt(windowEdge)
                .and("sendCount").lt(props.maxSendsPerHour()));
        return mongo.findAndModify(windowActive, challengeUpdate(now, otpHash, data).inc("sendCount", 1), PendingRegistration.class) != null;
    }

    private Update challengeUpdate(Instant now, String otpHash, PendingData d) {
        Update u = new Update().set("otpHash", otpHash).set("otpExpiresAt", now.plus(props.ttl())).set("failedAttempts", 0).set("lastSentAt", now)
                .set("expireAt", now.plus(props.pendingRetention()));
        if (d != null) { // a repeated registration for the same address replaces the details (the old code stops working)
            u.set("username", d.username()).set("name", d.name()).set("passwordHash", d.passwordHash()).set("phone", d.phone()).set("city", d.city()).set("role", d.role());
        }
        return u;
    }

    private void deliver(String email, String otp, String otpHash) {
        try {
            emailService.sendVerificationCode(email, otp, Math.max(1, props.ttl().toMinutes()));
        } catch (MailException ex) {
            log.warn("Verification e-mail delivery failed ({})", ex.getClass().getSimpleName()); // never log the code, the address or the message
            // Drop the undelivered code and release the cooldown so the user can retry immediately.
            mongo.updateFirst(Query.query(Criteria.where("email").is(email).and("otpHash").is(otpHash)), new Update().set("otpHash", null).set("lastSentAt", Instant.EPOCH),
                    PendingRegistration.class);
            throw ApiException.serviceUnavailable("We could not send the verification email. Please try again shortly.");
        }
    }

    private ApiException tooManyRequests(String email, Instant now) {
        long retryAfter = pendingRepository.findByEmail(email).map(p -> {
            Instant cooldownEnds = p.getLastSentAt().plus(props.resendCooldown());
            Instant windowEnds = p.getSendWindowStart().plus(SEND_WINDOW);
            boolean capped = p.getSendCount() >= props.maxSendsPerHour() && windowEnds.isAfter(now);
            Instant next = capped && windowEnds.isAfter(cooldownEnds) ? windowEnds : cooldownEnds;
            return Duration.between(now, next).toSeconds();
        }).orElse(props.resendCooldown().toSeconds());
        return ApiException.rateLimited("Too many verification emails requested. Please wait before trying again.", Math.max(1, retryAfter));
    }

    private static ApiException invalidCode() {
        return new ApiException(400, "INVALID_CODE", "Invalid or expired verification code", null);
    }
}
