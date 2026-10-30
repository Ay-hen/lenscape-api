package photography.photography.account;

import java.util.List;
import java.util.Locale;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.domain.Geo;
import photography.photography.model.Misc.CustomerExtras;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.User;
import photography.photography.model.Values.NotificationPrefs;
import photography.photography.model.Values.SavedLocation;
import photography.photography.respository.MiscRepos.CustomerExtrasRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.TokenRepo;
import photography.photography.respository.UserRepo;

/** Current-user helpers, the customer profile, and password changes. */
@Service
public class AccountService {
    private final UserRepo users;
    private final PhotographerProfileRepo profiles;
    private final CustomerExtrasRepo extras;
    private final TokenRepo tokens;
    private final PasswordEncoder encoder;
    private final MongoTemplate mongo;

    public AccountService(UserRepo users, PhotographerProfileRepo profiles, CustomerExtrasRepo extras, TokenRepo tokens, PasswordEncoder encoder, MongoTemplate mongo) {
        this.users = users;
        this.profiles = profiles;
        this.extras = extras;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mongo = mongo;
    }

    /** The signed-in user; 401 when the principal is missing or the account no longer exists. */
    public User current(String username) {
        if (username == null) {
            throw ApiException.unauthenticated("Sign in required.");
        }
        return users.findByUsername(username).orElseThrow(() -> ApiException.unauthenticated("Sign in required."));
    }

    public UserDto dto(User u) {
        String approval = "photographer".equals(u.getRole()) ? profiles.findById(u.getUsername()).map(PhotographerProfile::getApproval).orElse("draft") : null;
        return UserDto.of(u, approval);
    }

    public UserDto me(String username) {
        return dto(current(username));
    }

    public void touch(String username) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(username)), new Update().set("lastActiveAt", java.time.Instant.now()), User.class);
    }

    // ------------------------------------------------------------------ customer profile

    public record CustomerProfileDto(String id, String role, String name, String username, String email, String phone, String avatarUrl, String city,
                                     String status, java.time.Instant createdAt, java.time.Instant lastActiveAt,
                                     List<SavedLocation> savedLocations, NotificationPrefs notificationPrefs) {
    }

    /** {@code avatarUrl}: null = leave as is, "" = go back to the default picture, otherwise an uploaded/http(s) image address. */
    public record CustomerPatch(String name, String email, String phone, String city, List<SavedLocation> savedLocations, NotificationPrefs notificationPrefs, String avatarUrl) {
        public CustomerPatch(String name, String email, String phone, String city, List<SavedLocation> savedLocations, NotificationPrefs notificationPrefs) {
            this(name, email, phone, city, savedLocations, notificationPrefs, null);
        }
    }

    public CustomerProfileDto customer(String username) {
        User u = requireClient(username);
        CustomerExtras x = extras.findById(username).orElseGet(() -> {
            CustomerExtras e = new CustomerExtras();
            e.setUsername(username);
            return e;
        });
        UserDto d = dto(u);
        return new CustomerProfileDto(d.id(), d.role(), d.name(), d.username(), d.email(), d.phone(), d.avatarUrl(), d.city(), d.status(), d.createdAt(), d.lastActiveAt(),
                x.getSavedLocations(), x.getNotificationPrefs());
    }

    public CustomerProfileDto updateCustomer(String username, CustomerPatch p) {
        User u = requireClient(username);
        Update upd = new Update();
        if (p.name() != null) {
            String n = p.name().trim();
            if (n.length() < 2 || n.length() > 80) {
                throw ApiException.validation("Name must be 2-80 characters.", "name", "2-80 characters");
            }
            upd.set("name", n);
        }
        if (p.email() != null) {
            String e = p.email().trim().toLowerCase(Locale.ROOT);
            if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || e.length() > 254) {
                throw ApiException.validation("Enter a valid e-mail address.", "email", "Invalid e-mail");
            }
            if (!e.equalsIgnoreCase(u.getEmail())) {
                // The address is a verified identity: it cannot be swapped for an unverified one. (An e-mail change flow with its own code is not built yet.)
                throw ApiException.validation("Changing your email address requires verification, which is not available yet. Contact support.", "email", "Cannot be changed here");
            }
        }
        if (p.phone() != null) {
            if (p.phone().length() > 30) {
                throw ApiException.validation("Phone number is too long.", "phone", "Too long");
            }
            upd.set("phone", p.phone().trim());
        }
        if (p.city() != null) {
            if (p.city().length() > 60) {
                throw ApiException.validation("City name is too long.", "city", "Too long");
            }
            upd.set("city", p.city().trim());
        }
        if (p.avatarUrl() != null) {
            upd.set("avatarUrl", photography.photography.api.Check.url(p.avatarUrl(), "avatarUrl"));
        }
        if (!upd.getUpdateObject().isEmpty()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(username)), upd, User.class);
        }
        if (p.savedLocations() != null || p.notificationPrefs() != null) {
            CustomerExtras x = extras.findById(username).orElseGet(() -> {
                CustomerExtras e = new CustomerExtras();
                e.setUsername(username);
                return e;
            });
            if (p.savedLocations() != null) {
                if (p.savedLocations().size() > 10) {
                    throw ApiException.validation("You can save up to 10 locations.");
                }
                for (SavedLocation l : p.savedLocations()) {
                    if (l.label() == null || l.label().isBlank() || l.label().length() > 60 || l.address() == null || l.address().length() > 200
                            || l.point() == null || Math.abs(l.point().lat()) > 90 || Math.abs(l.point().lng()) > 180) {
                        throw ApiException.validation("A saved location is incomplete or invalid.");
                    }
                }
                x.setSavedLocations(new java.util.ArrayList<>(p.savedLocations()));
            }
            if (p.notificationPrefs() != null) {
                x.setNotificationPrefs(p.notificationPrefs());
            }
            extras.save(x);
        }
        return customer(username);
    }

    private User requireClient(String username) {
        User u = current(username);
        if (!"client".equals(u.getRole())) {
            throw ApiException.forbidden("Only customer accounts have a customer profile.");
        }
        return u;
    }

    // ------------------------------------------------------------------ password

    /** Verifies the current password, stores the new hash and ends every other session. */
    public void changePassword(String username, String currentPassword, String next, String currentToken) {
        User u = current(username);
        if (!encoder.matches(currentPassword, u.getPassword())) {
            throw ApiException.validation("Current password is incorrect.", "current", "Incorrect password");
        }
        if (encoder.matches(next, u.getPassword())) {
            throw ApiException.validation("Choose a password you have not used before.", "next", "Same as the current password");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(username)), new Update().set("password", encoder.encode(next)), User.class);
        tokens.findByUsername(username).stream().filter(t -> !t.getToken().equals(currentToken)).forEach(tokens::delete);
    }

    public static boolean knownCity(String city) {
        return Geo.canonicalCity(city) != null;
    }
}
