package photography.photography.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.Values.CategoryDef;
import photography.photography.model.Values.FaqItem;
import photography.photography.model.Values.NamedTemplate;
import photography.photography.respository.MiscRepos.SettingsRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;

/** Platform-wide settings: one document, always present (created with reference defaults at first start). */
@Service
public class SettingsService {
    private final SettingsRepo repo;
    private final PhotographerProfileRepo profiles;
    private final ServiceOfferRepo services;

    public SettingsService(SettingsRepo repo, PhotographerProfileRepo profiles, ServiceOfferRepo services) {
        this.repo = repo;
        this.profiles = profiles;
        this.services = services;
    }

    /** Reference data (categories, FAQ, templates) the application cannot work without. It is configuration, not demo content. */
    public static PlatformSettings defaults() {
        PlatformSettings s = new PlatformSettings();
        s.setTagline("Book trusted photographers near you");
        s.setCategories(new ArrayList<>(List.of(
                new CategoryDef("wedding", "weddings", "Weddings", "heart", "img/cat-wedding.jpg", true),
                new CategoryDef("engagement", "engagements", "Engagements", "gem", "img/cat-engagement.jpg", false),
                new CategoryDef("portrait", "portraits", "Portraits", "user", "img/cat-portrait.jpg", true),
                new CategoryDef("event", "events", "Events", "party-popper", "img/cat-event.jpg", true),
                new CategoryDef("birthday", "birthdays", "Birthdays", "cake", "img/cat-birthday.jpg", true),
                new CategoryDef("corporate", "corporate", "Corporate", "briefcase", "img/cat-corporate.jpg", false),
                new CategoryDef("family", "family", "Family", "users", "img/cat-family.jpg", true),
                new CategoryDef("product", "product", "Product", "package", "img/cat-product.jpg", false),
                new CategoryDef("fashion", "fashion", "Fashion", "shirt", "img/cat-fashion.jpg", false),
                new CategoryDef("travel", "travel", "Travel", "plane", "img/cat-travel.jpg", false),
                new CategoryDef("real-estate", "real-estate", "Real estate", "home", "img/cat-real-estate.jpg", false))));
        s.setFaqs(new ArrayList<>(List.of(
                new FaqItem("f1", "How does booking work?", "Choose a photographer, pick a package and an available time, and send your request. The photographer confirms it — you are only booked once the status says Confirmed."),
                new FaqItem("f2", "When do I pay?", "Online payment is not available yet. You see the exact amount on every booking, and its payment status stays Unpaid until online payment launches."),
                new FaqItem("f3", "Can I cancel or reschedule?", "Yes. Each photographer shows a cancellation policy on their profile and at checkout. Reschedule requests are sent to the other side for approval."),
                new FaqItem("f4", "What does the “Verified identity” badge mean?", "It only appears when our team has actually checked a photographer’s ID. “Approved” means the profile passed a quality review — it is a different thing."),
                new FaqItem("f5", "Where is the photographer’s exact address?", "Freelancers never share an exact address: the map shows an approximate area inside their city. Photographers with a shop or studio publish its exact position so you can find them."))));
        s.setNotificationTemplates(new ArrayList<>(List.of(
                new NamedTemplate("n1", "Booking received", "Hi {{name}}, we received your request {{reference}}."),
                new NamedTemplate("n2", "Booking confirmed", "Good news — {{photographer}} confirmed your session on {{date}}."))));
        return s;
    }

    public PlatformSettings get() {
        return repo.findById(PlatformSettings.ID).orElseGet(() -> repo.save(defaults()));
    }

    public Set<String> categoryIds() {
        Set<String> ids = new HashSet<>();
        get().getCategories().forEach(c -> ids.add(c.id()));
        return ids;
    }

    public PlatformSettings save(PlatformSettings in) {
        PlatformSettings cur = get();
        if (in.getCommissionPercent() < 0 || in.getCommissionPercent() > 40) {
            throw ApiException.validation("Commission must be 0–40%.", "commissionPercent", "0–40");
        }
        if (in.getBrandName() == null || in.getBrandName().isBlank() || in.getBrandName().length() > 40) {
            throw ApiException.validation("Brand name is required (max 40 characters).", "brandName", "Required");
        }
        if (in.getTagline() != null && in.getTagline().length() > 140) {
            throw ApiException.validation("Tagline is too long.", "tagline", "Max 140 characters");
        }
        if (!List.of("MAD", "EUR", "USD").contains(in.getCurrency())) {
            throw ApiException.validation("Unsupported currency.", "currency", "MAD, EUR or USD");
        }
        if (in.getMaintenanceBanner() != null && in.getMaintenanceBanner().length() > 300) {
            throw ApiException.validation("The banner is too long.", "maintenanceBanner", "Max 300 characters");
        }
        if (in.getDefaultFreeCancelHours() < 0 || in.getDefaultFreeCancelHours() > 720 || in.getDefaultMinNoticeHours() < 0 || in.getDefaultMinNoticeHours() > 720) {
            throw ApiException.validation("Default hours must be between 0 and 720.");
        }
        // categories: keep the icon/image/slug the admin form does not edit, validate ids, never drop a category that services use
        List<CategoryDef> next = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (in.getCategories() == null || in.getCategories().isEmpty()) {
            throw ApiException.validation("Keep at least one category.");
        }
        for (CategoryDef c : in.getCategories()) {
            if (c.id() == null || !c.id().matches("^[a-z0-9-]{2,30}$") || !seen.add(c.id())) {
                throw ApiException.validation("Category ids must be unique, lower-case letters, digits or dashes.");
            }
            if (c.name() == null || c.name().isBlank() || c.name().length() > 40) {
                throw ApiException.validation("Each category needs a name (max 40 characters).");
            }
            CategoryDef old = cur.getCategories().stream().filter(o -> o.id().equals(c.id())).findFirst().orElse(null);
            next.add(new CategoryDef(c.id(), old != null ? old.slug() : c.id(), c.name().trim(), old != null ? old.icon() : "camera",
                    old != null ? old.image() : "img/cat-event.jpg", c.featured()));
        }
        Set<String> removed = new HashSet<>();
        cur.getCategories().forEach(o -> {
            if (!seen.contains(o.id())) {
                removed.add(o.id());
            }
        });
        if (!removed.isEmpty()) {
            for (ServiceOffer s : services.findAll()) {
                if (removed.contains(s.getCategoryId())) {
                    throw ApiException.conflict("Category “" + s.getCategoryId() + "” is used by existing services and cannot be removed.");
                }
            }
        }
        if (in.getFaqs() == null || in.getFaqs().size() > 30 || in.getFaqs().stream().anyMatch(f -> f.q() == null || f.q().isBlank() || f.q().length() > 200 || f.a() == null || f.a().length() > 2000)) {
            throw ApiException.validation("FAQ entries need a question (max 200) and an answer (max 2000); at most 30 entries.");
        }
        if (in.getNotificationTemplates() == null || in.getNotificationTemplates().size() > 30
                || in.getNotificationTemplates().stream().anyMatch(t -> t.name() == null || t.name().isBlank() || t.name().length() > 80 || t.body() == null || t.body().length() > 2000)) {
            throw ApiException.validation("Templates need a name (max 80) and a body (max 2000); at most 30 templates.");
        }
        List<String> featuredIds = in.getFeaturedPhotographerIds() == null ? List.of() : in.getFeaturedPhotographerIds();
        if (featuredIds.size() > 50) {
            throw ApiException.validation("Too many featured photographers.");
        }
        cur.setBrandName(in.getBrandName().trim());
        cur.setTagline(in.getTagline() == null ? "" : in.getTagline().trim());
        cur.setCurrency(in.getCurrency());
        cur.setLocale(in.getLocale() == null || in.getLocale().length() > 10 ? cur.getLocale() : in.getLocale());
        cur.setCommissionPercent(in.getCommissionPercent());
        cur.setDefaultFreeCancelHours(in.getDefaultFreeCancelHours());
        cur.setDefaultMinNoticeHours(in.getDefaultMinNoticeHours());
        cur.setCategories(next);
        cur.setFaqs(new ArrayList<>(in.getFaqs()));
        cur.setNotificationTemplates(new ArrayList<>(in.getNotificationTemplates()));
        cur.setMaintenanceBanner(in.getMaintenanceBanner() == null ? "" : in.getMaintenanceBanner().trim());
        // keep the profile flag used for ranking in step with the admin's featured list
        Set<String> featured = new HashSet<>(featuredIds);
        List<String> valid = new ArrayList<>();
        for (PhotographerProfile p : profiles.findAll()) {
            boolean want = featured.contains(p.getId());
            if (want) {
                valid.add(p.getId());
            }
            if (p.isFeatured() != want) {
                p.setFeatured(want);
                profiles.save(p);
            }
        }
        cur.setFeaturedPhotographerIds(valid);
        return repo.save(cur);
    }
}
