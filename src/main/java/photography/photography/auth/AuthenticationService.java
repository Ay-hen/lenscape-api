package photography.photography.auth;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import photography.photography.account.AccountService;
import photography.photography.api.ApiException;
import photography.photography.catalog.ProfileProvisioner;
import photography.photography.domain.Geo;
import photography.photography.model.Token;
import photography.photography.model.User;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;
import photography.photography.service.JwtService;
import photography.photography.verification.OtpService;
import photography.photography.verification.OtpService.PendingData;
import photography.photography.verification.PendingRegistration;

@Service
public class AuthenticationService {
    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 15 * 60_000L;

    private final TokenRepo tokenRepo;
    private final UserRepo repo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ProfileProvisioner provisioner;
    private final AccountService accounts;
    private final OtpService otpService;
    /** Hash compared against when the username does not exist, so response time does not reveal which usernames are registered. */
    private final String dummyHash;
    /** Failed sign-in timestamps per login name (in memory, per instance). */
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public AuthenticationService(TokenRepo tokenRepo, UserRepo repo, PasswordEncoder passwordEncoder, JwtService jwtService,
                                 ProfileProvisioner provisioner, AccountService accounts, OtpService otpService) {
        this.tokenRepo = tokenRepo;
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.provisioner = provisioner;
        this.accounts = accounts;
        this.otpService = otpService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /**
     * Step 1 of sign-up. Validates the form and e-mails a 6-digit code; NO account is created and no token is issued until the code is confirmed
     * (see {@link #verifyEmail}). Re-submitting for the same address (still unverified) replaces the pending details and sends a fresh code.
     */
    public RegistrationResponse register(RegisterRequest request) {
        String username = request.getUsername().trim().toLowerCase(Locale.ROOT);
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String role = request.getRole() == null || request.getRole().isBlank() ? "client" : request.getRole();
        if (!"client".equals(role) && !"photographer".equals(role)) {
            throw ApiException.validation("Role must be client or photographer.", "role", "Not allowed");
        }
        Map<String, String> bad = new LinkedHashMap<>();
        if ("photographer".equals(role)) {
            if (request.getPhone() == null || request.getPhone().trim().length() < 6) {
                bad.put("phone", "Enter a phone number");
            }
            if (Geo.canonicalCity(request.getCity()) == null) {
                bad.put("city", "Choose a city from the list");
            }
        }
        if (!bad.isEmpty()) {
            throw new ApiException(400, "VALIDATION", "Please fix the highlighted fields.", bad);
        }
        if (repo.existsByUsername(username)) {
            throw ApiException.conflict("That username is taken.", "username", "Already taken");
        }
        if (repo.existsByEmail(email)) {
            throw ApiException.conflict("That email is already registered.", "email", "Already registered");
        }
        otpService.startRegistration(new PendingData(email, username, request.getName().trim(), passwordEncoder.encode(request.getPassword()),
                request.getPhone() == null ? null : request.getPhone().trim(),
                "photographer".equals(role) ? Geo.canonicalCity(request.getCity()) : blankToNull(request.getCity()), role));
        return new RegistrationResponse("Verification required. We sent a 6-digit code to your email.", email, true, otpService.ttlSeconds());
    }

    /** Step 2: the code proves the person controls the address. Only now is the account created, and the user is signed in. */
    public AuthenticationResponse verifyEmail(String emailIn, String otp) {
        String email = emailIn.trim().toLowerCase(Locale.ROOT);
        PendingRegistration p = otpService.verify(email, otp);
        Instant now = Instant.now();
        if (repo.existsByEmail(email)) {
            throw ApiException.conflict("That email is already registered.", "email", "Already registered");
        }
        User user = User.builder()
                .username(p.getUsername())
                .name(p.getName())
                .email(p.getEmail())
                .phone(p.getPhone())
                .password(p.getPasswordHash())
                .role(p.getRole())
                .city(p.getCity())
                .suspended(false)
                .emailVerified(true)
                .emailVerifiedAt(now)
                .createdAt(now)
                .reservations(new ArrayList<>())
                .build();
        try {
            repo.insert(user); // insert, not save: a racing duplicate must fail instead of overwriting another account
        } catch (DuplicateKeyException race) {
            if (repo.existsByUsername(p.getUsername())) {
                throw ApiException.conflict("That username was taken while you were verifying. Please register again with another username.", "username", "Already taken");
            }
            throw ApiException.conflict("That email is already registered.", "email", "Already registered");
        }
        if ("photographer".equals(user.getRole())) {
            provisioner.provision(user);
        }
        return issue(user);
    }

    public void resendCode(String emailIn) {
        otpService.resend(emailIn.trim().toLowerCase(Locale.ROOT));
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        String login = request.getUsername().trim();
        String key = login.toLowerCase(Locale.ROOT);
        checkNotThrottled(key);
        User user = findForLogin(login);
        String hash = user != null ? user.getPassword() : dummyHash;
        boolean matches = hash != null && passwordEncoder.matches(request.getPassword(), hash);
        if (user == null || !matches) {
            recordFailure(key);
            throw ApiException.unauthenticated("Incorrect username or password.");
        }
        if (user.isSuspended()) {
            throw ApiException.forbidden("This account is suspended. Contact support.");
        }
        failures.remove(key);
        accounts.touch(user.getUsername());
        return issue(user);
    }

    /** Revokes the token used for this request. Idempotent. */
    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return;
        }
        tokenRepo.deleteById(authorizationHeader.substring(7));
    }

    private AuthenticationResponse issue(User user) {
        String jwt = jwtService.generateToken(user);
        Token tk = Token.builder().token(jwt).username(user.getUsername()).expired(false).revoked(false)
                .expiresAt(jwtService.extractExpiration(jwt)).build();
        tokenRepo.save(tk);
        return AuthenticationResponse.builder().token(jwt).role(user.getRole()).user(accounts.dto(user)).build();
    }

    private User findForLogin(String login) {
        if (login.contains("@")) {
            return repo.findByEmail(login.toLowerCase(Locale.ROOT)).orElse(null);
        }
        User exact = repo.findByUsername(login).orElse(null);
        return exact != null ? exact : repo.findByUsername(login.toLowerCase(Locale.ROOT)).orElse(null);
    }

    private void checkNotThrottled(String key) {
        Deque<Long> q = failures.get(key);
        if (q == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= MAX_FAILURES) {
                throw new ApiException(429, "RATE_LIMITED", "Too many failed attempts. Try again in a few minutes.", null);
            }
        }
    }

    private void recordFailure(String key) {
        Deque<Long> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
        }
        if (failures.size() > 10_000) { // bound memory under a username-spraying attack
            long now = System.currentTimeMillis();
            failures.values().removeIf(d -> d.isEmpty() || now - d.peekLast() > WINDOW_MS);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
