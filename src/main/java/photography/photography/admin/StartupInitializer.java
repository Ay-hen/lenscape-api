package photography.photography.admin;

import java.time.Instant;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import photography.photography.catalog.ProfileProvisioner;
import photography.photography.model.User;
import photography.photography.respository.UserRepo;

/**
 * Idempotent start-up tasks. Nothing here deletes or overwrites data:
 * 1. reference data (categories, FAQ, templates) is created once;
 * 2. the first administrator is created from APP_ADMIN_* when that username does not exist;
 * 3. accounts from the previous version of the API are completed (created-at timestamp, photographer profile as a DRAFT).
 */
@Component
public class StartupInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final SettingsService settings;
    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final ProfileProvisioner provisioner;
    private final MongoTemplate mongo;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminEmail;
    private final String adminName;

    public StartupInitializer(SettingsService settings, UserRepo users, PasswordEncoder encoder, ProfileProvisioner provisioner, MongoTemplate mongo,
                              @Value("${app.admin.username:}") String adminUsername, @Value("${app.admin.password:}") String adminPassword,
                              @Value("${app.admin.email:}") String adminEmail, @Value("${app.admin.name:Administrator}") String adminName) {
        this.settings = settings;
        this.users = users;
        this.encoder = encoder;
        this.provisioner = provisioner;
        this.mongo = mongo;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.adminEmail = adminEmail;
        this.adminName = adminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        settings.get();
        ensureUniqueEmailIndex();
        bootstrapAdmin();
        mongo.updateMulti(Query.query(Criteria.where("createdAt").is(null)), new Update().set("createdAt", Instant.now()), User.class);
        for (User u : users.findByRole("photographer")) {
            provisioner.provision(u);
        }
        // Photographers are live from sign-up (no review step): bring profiles that were waiting for a review into the same state.
        mongo.updateMulti(Query.query(Criteria.where("approval").in("draft", "pending", "needs_info")), new Update().set("approval", "approved"),
                photography.photography.model.PhotographerProfile.class);
    }

    /** E-mail addresses identify verified accounts, so they must be unique. If old data contains duplicates the index cannot be built: say so clearly instead of crashing. */
    private void ensureUniqueEmailIndex() {
        try {
            mongo.indexOps(User.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("email", org.springframework.data.domain.Sort.Direction.ASC)
                    .unique().sparse().named("uk_users_email"));
        } catch (RuntimeException e) {
            log.error("Could not create the unique index on user e-mail addresses (duplicate addresses in the user collection?). "
                    + "Resolve the duplicates; registration still checks uniqueness in code meanwhile. Cause: {}", e.getClass().getSimpleName());
        }
    }

    private void bootstrapAdmin() {
        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            if (users.countByRole("admin") == 0) {
                log.warn("No administrator account exists. Set APP_ADMIN_USERNAME, APP_ADMIN_PASSWORD (12+ characters) and APP_ADMIN_EMAIL and restart to create one.");
            }
            return;
        }
        if (users.existsByUsername(adminUsername)) {
            return;
        }
        if (adminPassword.length() < 12 || adminPassword.length() > 72) {
            log.error("APP_ADMIN_PASSWORD must be 12-72 characters. The administrator account was NOT created.");
            return;
        }
        if (adminEmail.isBlank() || users.existsByEmail(adminEmail.toLowerCase())) {
            log.error("APP_ADMIN_EMAIL is missing or already used. The administrator account was NOT created.");
            return;
        }
        User admin = User.builder().username(adminUsername).name(adminName).email(adminEmail.toLowerCase()).password(encoder.encode(adminPassword))
                .role("admin").suspended(false).emailVerified(true).emailVerifiedAt(Instant.now()).createdAt(Instant.now()).reservations(new ArrayList<>()).build();
        users.insert(admin);
        log.info("Administrator account '{}' created.", adminUsername);
    }
}
