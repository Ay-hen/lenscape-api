package photography.photography.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mail.MailSendException;

import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.verification.OtpService.PendingData;

/** Branch logic of the OTP service with MongoDB mocked. (Atomicity itself is MongoDB's job and needs an integration test against a real database.) */
class OtpServiceImplTest {
    PendingRegistrationRepo repo = mock(PendingRegistrationRepo.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    EmailService email = mock(EmailService.class);
    OtpProperties props = OtpHasherTest.props("0123456789abcdef0123456789abcdef-secret", false);
    OtpHasher hasher = new OtpHasher(props);
    OtpServiceImpl service;
    final PendingData data = new PendingData("alice@example.com", "alice", "Alice", "bcrypt-hash", "0612345678", "Rabat", "client");

    @BeforeEach
    void setUp() {
        service = new OtpServiceImpl(repo, mongo, email, hasher, props, new PlatformClock("Africa/Casablanca"));
    }

    private PendingRegistration live(int failedAttempts, String code) {
        PendingRegistration p = new PendingRegistration();
        p.setId("p1");
        p.setEmail(data.email());
        p.setOtpHash(hasher.hash(data.email(), code));
        p.setFailedAttempts(failedAttempts);
        p.setLastSentAt(Instant.now());
        p.setSendWindowStart(Instant.now());
        p.setSendCount(1);
        return p;
    }

    @SuppressWarnings("unchecked")
    private void attemptReturns(PendingRegistration p) {
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PendingRegistration.class))).thenReturn(p);
    }

    @Test
    void firstRegistrationStoresOnlyTheHashAndEmailsTheCode() {
        service.startRegistration(data);
        ArgumentCaptor<PendingRegistration> saved = ArgumentCaptor.forClass(PendingRegistration.class);
        verify(repo).insert(saved.capture());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(email).sendVerificationCode(eq("alice@example.com"), code.capture(), anyLong());
        assertTrue(code.getValue().matches("\\d{6}"));
        PendingRegistration p = saved.getValue();
        assertEquals(hasher.hash("alice@example.com", code.getValue()), p.getOtpHash());
        assertTrue(p.getOtpHash() != null && !p.getOtpHash().contains(code.getValue()));
        assertEquals("bcrypt-hash", p.getPasswordHash());
        assertEquals("alice", p.getUsername());
        assertEquals("client", p.getRole());
        assertEquals(0, p.getFailedAttempts());
        assertTrue(p.getOtpExpiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(9))));
        assertTrue(p.getExpireAt().isAfter(p.getOtpExpiresAt()), "the pending record outlives the code");
    }

    @Test
    void undeliverableMailDropsTheCodeAndAnswers503() {
        doThrow(new MailSendException("smtp down")).when(email).sendVerificationCode(anyString(), anyString(), anyLong());
        ApiException e = assertThrows(ApiException.class, () -> service.startRegistration(data));
        assertEquals(503, e.status());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PendingRegistration.class)); // code invalidated + cooldown released
    }

    @Test
    void secondRegistrationWithinTheCooldownIsRateLimitedWithRetryAfter() {
        when(repo.existsByEmail("alice@example.com")).thenReturn(true);
        when(repo.findByEmail("alice@example.com")).thenReturn(Optional.of(live(0, "111111"))); // both slot claims match nothing (mock default null)
        ApiException e = assertThrows(ApiException.class, () -> service.startRegistration(data));
        assertEquals(429, e.status());
        assertEquals("RATE_LIMITED", e.code());
        assertTrue(e.retryAfterSeconds() >= 1 && e.retryAfterSeconds() <= 60);
        verify(email, never()).sendVerificationCode(anyString(), anyString(), anyLong());
    }

    @Test
    void resendForUnknownAddressLooksLikeSuccessAndSendsNothing() {
        when(repo.existsByEmail("ghost@example.com")).thenReturn(false);
        service.resend("ghost@example.com");
        verify(email, never()).sendVerificationCode(anyString(), anyString(), anyLong());
    }

    @Test
    void resendInsideTheCooldownIs429() {
        when(repo.existsByEmail("alice@example.com")).thenReturn(true);
        when(repo.findByEmail("alice@example.com")).thenReturn(Optional.of(live(0, "111111")));
        assertEquals(429, assertThrows(ApiException.class, () -> service.resend("alice@example.com")).status());
    }

    @Test
    void resendWhenASlotIsAvailableSendsANewCode() {
        when(mongo.findAndModify(any(Query.class), any(Update.class), eq(PendingRegistration.class))).thenReturn(live(0, "111111"));
        service.resend("alice@example.com");
        verify(email).sendVerificationCode(eq("alice@example.com"), anyString(), anyLong());
    }

    @Test
    void correctCodeConsumesThePendingRegistration() {
        PendingRegistration p = live(1, "482913");
        attemptReturns(p);
        when(mongo.findAndRemove(any(Query.class), eq(PendingRegistration.class))).thenReturn(p);
        assertSame(p, service.verify("alice@example.com", "482913"));
    }

    @Test
    void wrongCodeIsRejectedAndCountedButNotYetLocked() {
        attemptReturns(live(2, "482913"));
        ApiException e = assertThrows(ApiException.class, () -> service.verify("alice@example.com", "000000"));
        assertEquals(400, e.status());
        assertEquals("INVALID_CODE", e.code());
        verify(mongo, never()).findAndRemove(any(Query.class), eq(PendingRegistration.class));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PendingRegistration.class));
    }

    @Test
    void theFifthWrongCodeInvalidatesTheChallengeAndAnswers429() {
        attemptReturns(live(5, "482913")); // the atomic increment just reached the limit
        ApiException e = assertThrows(ApiException.class, () -> service.verify("alice@example.com", "000000"));
        assertEquals(429, e.status());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PendingRegistration.class)); // otpHash set to null: even the right code is now dead
    }

    @Test
    void expiredExhaustedOrMissingChallengeIsAnInvalidCode() {
        attemptReturns(null); // the atomic query matched nothing
        assertEquals(400, assertThrows(ApiException.class, () -> service.verify("alice@example.com", "482913")).status());
    }

    @Test
    void aCodeCanBeConsumedOnlyOnce() {
        attemptReturns(live(1, "482913"));
        when(mongo.findAndRemove(any(Query.class), eq(PendingRegistration.class))).thenReturn(null); // a concurrent request won the race
        assertEquals(400, assertThrows(ApiException.class, () -> service.verify("alice@example.com", "482913")).status());
        assertNotNull(service.ttlSeconds());
    }
}
