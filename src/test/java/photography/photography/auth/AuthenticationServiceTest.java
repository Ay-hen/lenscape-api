package photography.photography.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import photography.photography.account.AccountService;
import photography.photography.api.ApiException;
import photography.photography.catalog.ProfileProvisioner;
import photography.photography.model.Token;
import photography.photography.model.User;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;
import photography.photography.service.JwtService;
import photography.photography.verification.OtpService;
import photography.photography.verification.OtpService.PendingData;
import photography.photography.verification.PendingRegistration;
import org.springframework.dao.DuplicateKeyException;

class AuthenticationServiceTest {
    TokenRepo tokens = mock(TokenRepo.class);
    UserRepo users = mock(UserRepo.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    JwtService jwt = mock(JwtService.class);
    ProfileProvisioner provisioner = mock(ProfileProvisioner.class);
    AccountService accounts = mock(AccountService.class);
    OtpService otp = mock(OtpService.class);
    AuthenticationService service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenAnswer(i -> "hash:" + i.getArgument(0));
        when(encoder.matches(any(CharSequence.class), anyString())).thenAnswer(i -> ("hash:" + i.getArgument(0)).equals(i.getArgument(1)));
        when(jwt.generateToken(any(User.class))).thenReturn("jwt-token");
        when(jwt.extractExpiration(anyString())).thenReturn(new Date());
        when(otp.ttlSeconds()).thenReturn(600L);
        service = new AuthenticationService(tokens, users, encoder, jwt, provisioner, accounts, otp);
    }

    RegisterRequest reg(String role) {
        return RegisterRequest.builder().username("Alice_1").name("Alice").email("Alice@Example.com").password("correct-horse").role(role).phone("0612345678").city("Rabat").build();
    }

    PendingRegistration pending(String role) {
        PendingRegistration p = new PendingRegistration();
        p.setEmail("alice@example.com");
        p.setUsername("alice_1");
        p.setName("Alice");
        p.setPasswordHash("hash:correct-horse");
        p.setPhone("0612345678");
        p.setCity("Rabat");
        p.setRole(role);
        return p;
    }

    @Test
    void registrationOnlyStartsVerificationNoAccountAndNoToken() {
        RegistrationResponse r = service.register(reg("client"));
        ArgumentCaptor<PendingData> d = ArgumentCaptor.forClass(PendingData.class);
        verify(otp).startRegistration(d.capture());
        assertEquals("alice_1", d.getValue().username());
        assertEquals("alice@example.com", d.getValue().email());
        assertEquals("hash:correct-horse", d.getValue().passwordHash(), "the password is hashed before it is even parked");
        assertEquals("client", d.getValue().role());
        assertEquals(true, r.verificationRequired());
        assertEquals("alice@example.com", r.email());
        assertEquals(600, r.otpExpiresInSeconds());
        verify(users, never()).insert(any(User.class));
        verify(tokens, never()).save(any(Token.class));
        verify(provisioner, never()).provision(any(User.class));
    }

    @Test
    void nobodyCanSelfRegisterAsAdminOrAnythingElse() {
        for (String role : new String[] {"admin", "root", "ADMIN"}) {
            assertEquals(400, assertThrows(ApiException.class, () -> service.register(reg(role))).status());
        }
        verify(otp, never()).startRegistration(any(PendingData.class));
    }

    @Test
    void photographersMustGiveAKnownCityAndPhoneBeforeAnyEmailIsSent() {
        RegisterRequest bad = reg("photographer");
        bad.setCity("Atlantis");
        bad.setPhone(null);
        ApiException e = assertThrows(ApiException.class, () -> service.register(bad));
        assertNotNull(e.errors().get("city"));
        assertNotNull(e.errors().get("phone"));
        verify(otp, never()).startRegistration(any(PendingData.class));
    }

    @Test
    void duplicateUsernameOrEmailIs409WithFieldErrorsAndNoEmailIsSent() {
        when(users.existsByUsername("alice_1")).thenReturn(true);
        ApiException e = assertThrows(ApiException.class, () -> service.register(reg("client")));
        assertEquals(409, e.status());
        assertNotNull(e.errors().get("username"));
        when(users.existsByUsername("alice_1")).thenReturn(false);
        when(users.existsByEmail("alice@example.com")).thenReturn(true);
        assertNotNull(assertThrows(ApiException.class, () -> service.register(reg("client"))).errors().get("email"));
        verify(otp, never()).startRegistration(any(PendingData.class));
    }

    @Test
    void confirmingTheCodeCreatesAVerifiedAccountAndSignsIn() {
        when(otp.verify("alice@example.com", "123456")).thenReturn(pending("client"));
        AuthenticationResponse r = service.verifyEmail("Alice@Example.com", "123456");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).insert(saved.capture());
        assertEquals("alice_1", saved.getValue().getUsername());
        assertEquals("client", saved.getValue().getRole());
        assertEquals("hash:correct-horse", saved.getValue().getPassword());
        assertEquals(Boolean.TRUE, saved.getValue().getEmailVerified());
        assertNotNull(saved.getValue().getEmailVerifiedAt());
        assertEquals("jwt-token", r.getToken());
        verify(tokens).save(any(Token.class));
        verify(provisioner, never()).provision(any(User.class));
    }

    @Test
    void verifiedPhotographersGetTheirDraftProfile() {
        when(otp.verify("alice@example.com", "123456")).thenReturn(pending("photographer"));
        service.verifyEmail("alice@example.com", "123456");
        verify(provisioner).provision(any(User.class));
    }

    @Test
    void aWrongCodeCreatesNothing() {
        when(otp.verify("alice@example.com", "000000")).thenThrow(new ApiException(400, "INVALID_CODE", "Invalid or expired verification code", null));
        assertEquals(400, assertThrows(ApiException.class, () -> service.verifyEmail("alice@example.com", "000000")).status());
        verify(users, never()).insert(any(User.class));
        verify(tokens, never()).save(any(Token.class));
    }

    @Test
    void usernameTakenWhileVerifyingIsAClearConflict() {
        when(otp.verify("alice@example.com", "123456")).thenReturn(pending("client"));
        when(users.insert(any(User.class))).thenThrow(new DuplicateKeyException("dup"));
        when(users.existsByUsername("alice_1")).thenReturn(true);
        ApiException e = assertThrows(ApiException.class, () -> service.verifyEmail("alice@example.com", "123456"));
        assertEquals(409, e.status());
        assertNotNull(e.errors().get("username"));
        verify(tokens, never()).save(any(Token.class));
    }

    @Test
    void anAddressRegisteredMeanwhileIsRefusedBeforeInserting() {
        when(otp.verify("alice@example.com", "123456")).thenReturn(pending("client"));
        when(users.existsByEmail("alice@example.com")).thenReturn(true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.verifyEmail("alice@example.com", "123456")).status());
        verify(users, never()).insert(any(User.class));
    }

    @Test
    void resendIsDelegatedWithANormalisedAddress() {
        service.resendCode("  Alice@Example.com ");
        verify(otp).resend("alice@example.com");
    }

    private User stored(boolean suspended) {
        return User.builder().username("bob").email("bob@example.com").password("hash:secret-pass").role("client").suspended(suspended).build();
    }

    @Test
    void loginByUsernameOrEmail() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(stored(false)));
        when(users.findByEmail("bob@example.com")).thenReturn(Optional.of(stored(false)));
        assertEquals("jwt-token", service.authenticate(new AuthenticationRequest("bob", "secret-pass")).getToken());
        assertEquals("jwt-token", service.authenticate(new AuthenticationRequest("Bob@Example.com", "secret-pass")).getToken());
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSameAndGetThrottled() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(stored(false)));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(new AuthenticationRequest("bob", "nope"))).status());
        }
        assertEquals(429, assertThrows(ApiException.class, () -> service.authenticate(new AuthenticationRequest("bob", "secret-pass"))).status());
        assertEquals("Incorrect username or password.", assertThrows(ApiException.class, () -> service.authenticate(new AuthenticationRequest("ghost", "x"))).getMessage());
    }

    @Test
    void suspendedAccountsCannotSignInButOnlyAfterTheCorrectPassword() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(stored(true)));
        assertEquals(401, assertThrows(ApiException.class, () -> service.authenticate(new AuthenticationRequest("bob", "wrong"))).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.authenticate(new AuthenticationRequest("bob", "secret-pass"))).status());
        verify(tokens, never()).save(any(Token.class));
    }

    @Test
    void logoutRevokesExactlyTheBearerToken() {
        service.logout("Bearer abc.def");
        verify(tokens).deleteById("abc.def");
        service.logout(null);
        service.logout("Basic zzz");
    }
}
