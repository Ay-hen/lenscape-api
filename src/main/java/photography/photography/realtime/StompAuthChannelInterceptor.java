package photography.photography.realtime;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import photography.photography.respository.UserRepo;
import photography.photography.service.JwtService;
import photography.photography.service.TokenService;

/**
 * Security for the WebSocket channel.
 * <ul>
 *   <li>CONNECT must carry {@code Authorization: Bearer <jwt>} as a STOMP header (never in the URL - URLs end up in logs).
 *       The JWT must be valid, not expired, still stored (not logged out) and belong to a non-suspended user.</li>
 *   <li>SUBSCRIBE is limited to the caller's private {@code /user/queue/**} destinations: nobody can listen to somebody else.</li>
 *   <li>SEND is limited to {@code /app/**} and requires the authenticated session.</li>
 * </ul>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private final JwtService jwt;
    private final TokenService tokens;
    private final UserRepo users;

    public StompAuthChannelInterceptor(JwtService jwt, TokenService tokens, UserRepo users) {
        this.jwt = jwt;
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            requireUser(accessor);
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith("/user/queue/")) {
                throw new MessagingException("Subscription not allowed");
            }
        } else if (StompCommand.SEND.equals(command)) {
            requireUser(accessor);
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith("/app/")) {
                throw new MessagingException("Destination not allowed");
            }
        }
        return message;
    }

    private UsernamePasswordAuthenticationToken authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Unauthorized");
        }
        String token = header.substring(7);
        try {
            String username = jwt.extractUsername(token); // throws on bad signature / expiry
            if (username == null || !tokens.isActive(token)) {
                throw new MessagingException("Unauthorized");
            }
            var user = users.findByUsername(username).orElseThrow(() -> new MessagingException("Unauthorized"));
            if (Boolean.TRUE.equals(user.getSuspended())) {
                throw new MessagingException("Unauthorized");
            }
            UserDetails details = user;
            return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        } catch (MessagingException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("STOMP CONNECT rejected: {}", e.toString());
            throw new MessagingException("Unauthorized");
        }
    }

    private static void requireUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessagingException("Unauthorized");
        }
    }

    /** Exposed for tests. */
    public static List<String> allowedPrefixes() {
        return List.of("/app/", "/user/queue/");
    }
}
