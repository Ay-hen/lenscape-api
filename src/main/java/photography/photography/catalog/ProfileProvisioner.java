package photography.photography.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import photography.photography.domain.Geo;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.User;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.MessageTemplate;
import photography.photography.respository.AvailabilityRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;
import photography.photography.respository.PhotographerProfileRepo;

/** Creates the profile, default availability and default automation rules every photographer account needs. Idempotent. */
@Component
public class ProfileProvisioner {
    private final PhotographerProfileRepo profiles;
    private final AvailabilityRepo availability;
    private final AutomationRepo automation;

    public ProfileProvisioner(PhotographerProfileRepo profiles, AvailabilityRepo availability, AutomationRepo automation) {
        this.profiles = profiles;
        this.availability = availability;
        this.automation = automation;
    }

    public static List<MessageTemplate> defaultTemplates() {
        return new ArrayList<>(List.of(
                new MessageTemplate("t1", "Booking confirmed", "Hi {{customer}}, your {{service}} on {{date}} at {{time}} is confirmed. Reference {{reference}}. Looking forward to it!", "message"),
                new MessageTemplate("t2", "Session reminder", "Reminder: your session is on {{date}} at {{time}} in {{city}}. Reply here if anything changes.", "message"),
                new MessageTemplate("t3", "Thank you & follow-up", "Thank you {{customer}}! Your gallery will be ready within {{days}} days.", "message"),
                new MessageTemplate("t4", "Custom quote", "Thanks for your interest! For {{service}} I would quote {{amount}} including {{inclusions}}.", "quotation")));
    }

    public PhotographerProfile provision(User u) {
        PhotographerProfile existing = profiles.findById(u.getUsername()).orElse(null);
        if (existing == null) {
            PhotographerProfile p = new PhotographerProfile();
            p.setId(u.getUsername());
            p.setUserId(u.getUsername());
            p.setDisplayName(u.getName());
            p.setApproval("approved"); // live immediately, no review step
            p.setPhone(u.getPhone());
            String city = Geo.canonicalCity(u.getCity());
            if (city == null) {
                city = "Casablanca";
            }
            p.setCity(city);
            GeoPoint base = Geo.CITY_COORDS.get(city);
            p.setPublicLocation(Geo.approximate(base, u.getUsername()));
            p.setLanguages(new ArrayList<>(List.of("Arabic", "French")));
            p.setJoinedAt(u.getCreatedAt() != null ? u.getCreatedAt() : Instant.now());
            profiles.insert(p);
            existing = p;
        }
        if (!availability.existsById(u.getUsername())) {
            availability.insert(AvailabilityRules.defaults(u.getUsername()));
        }
        if (!automation.existsById(u.getUsername())) {
            AutomationSettings a = new AutomationSettings();
            a.setPhotographerId(u.getUsername());
            a.setTemplates(defaultTemplates());
            a.setUpdatedAt(Instant.now());
            automation.insert(a);
        }
        return existing;
    }
}
