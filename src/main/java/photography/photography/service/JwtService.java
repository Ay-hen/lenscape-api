package photography.photography.service;

import java.security.Key;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    /** The signing key that used to be hard-coded in this repository. It is public, so it is refused at start-up. */
    private static final String LEAKED_DEFAULT = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    @Autowired
    private TokenService tokenService;

    /** Base64-encoded HS256 key of at least 32 bytes. Comes from env JWT_SECRET; never put a real value in source control. */
    @Value("${app.jwt.secret:}")
    private String secretKey;

    /** Development only (profile "dev"): generate a throw-away key at start-up when JWT_SECRET is not set. Tokens die on restart. */
    @Value("${app.jwt.allow-ephemeral-secret:false}")
    private boolean allowEphemeral;

    @Value("${app.jwt.expiration-minutes:1440}")
    private long expirationMinutes;

    private Key signingKey;

    @PostConstruct
    void init() {
        if (secretKey == null || secretKey.isBlank()) {
            if (!allowEphemeral) {
                throw new IllegalStateException("JWT_SECRET is not set. Generate one with `openssl rand -base64 48` and export it "
                        + "(see .env.example). For local development you may start with SPRING_PROFILES_ACTIVE=dev instead.");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            signingKey = Keys.hmacShaKeyFor(random);
            log.warn("JWT_SECRET is not set: using a temporary random key (profile dev). All tokens become invalid when the server restarts.");
            return;
        }
        String trimmed = secretKey.trim();
        if (LEAKED_DEFAULT.equals(trimmed)) {
            throw new IllegalStateException("JWT_SECRET is the old publicly known default key. Generate a new one with `openssl rand -base64 48`.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(trimmed);
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET must be Base64 encoded (`openssl rand -base64 48`).", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes (256 bits). Use `openssl rand -base64 48`.");
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setId(java.util.UUID.randomUUID().toString())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expirationMinutes * 60_000L))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Signature + expiry are verified while parsing; additionally the token must still be in the store (logout revokes it) and the account must be enabled. */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !tokenService.isExpired(token) && userDetails.isEnabled();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token).getBody();
    }
}
