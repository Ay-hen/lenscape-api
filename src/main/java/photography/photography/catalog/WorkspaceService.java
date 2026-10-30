package photography.photography.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import photography.photography.admin.SettingsService;
import photography.photography.api.ApiException;
import photography.photography.api.Check;
import photography.photography.catalog.CatalogDtos.ProfilePatch;
import photography.photography.domain.Dates;
import photography.photography.domain.Geo;
import photography.photography.model.Misc.Application;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Misc.PromoCodeList;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.User;
import photography.photography.model.Values.AddOn;
import photography.photography.model.Values.Album;
import photography.photography.model.Values.ApprovalEvent;
import photography.photography.model.Values.CancellationPolicy;
import photography.photography.model.Values.GearItem;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.MessageTemplate;
import photography.photography.model.Values.PortfolioImage;
import photography.photography.model.Values.PromoCode;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.MiscRepos.ApplicationRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;
import photography.photography.respository.MiscRepos.PromoRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;
import photography.photography.respository.UserRepo;

/** Everything a photographer edits about their own business. Every method works on the authenticated photographer only. */
@Service
public class WorkspaceService {
    private static final Set<String> GEAR_KINDS = Set.of("camera", "lens", "video", "drone", "lighting", "audio", "accessory");

    private final PhotographerProfileRepo profiles;
    private final ServiceOfferRepo services;
    private final ServicePackageRepo packages;
    private final PromoRepo promos;
    private final AutomationRepo automation;
    private final ApplicationRepo applications;
    private final UserRepo users;
    private final SettingsService settings;
    private final ProfileProvisioner provisioner;
    private final Notifier notifier;
    private final MongoTemplate mongo;

    public WorkspaceService(PhotographerProfileRepo profiles, ServiceOfferRepo services, ServicePackageRepo packages, PromoRepo promos, AutomationRepo automation,
                            ApplicationRepo applications, UserRepo users, SettingsService settings, ProfileProvisioner provisioner,
                            Notifier notifier, MongoTemplate mongo) {
        this.profiles = profiles;
        this.services = services;
        this.packages = packages;
        this.promos = promos;
        this.automation = automation;
        this.applications = applications;
        this.users = users;
        this.settings = settings;
        this.provisioner = provisioner;
        this.notifier = notifier;
        this.mongo = mongo;
    }

    public PhotographerProfile mine(String username) {
        return profiles.findById(username).orElseGet(() -> {
            User u = users.findByUsername(username).orElseThrow(() -> ApiException.unauthenticated("Sign in required."));
            return provisioner.provision(u);
        });
    }

    // ------------------------------------------------------------------ profile

    /** Whitelisted patch: approval, identity badge, rating, counters, location and work mode can never be set here. */
    public PhotographerProfile updateProfile(String username, ProfilePatch patch) {
        PhotographerProfile p = mine(username);
        if (patch.displayName() != null) {
            p.setDisplayName(Check.text(patch.displayName(), "displayName", 2, 80));
        }
        if (patch.businessName() != null) {
            String b = Check.text(patch.businessName(), "businessName", 0, 80);
            p.setBusinessName(b.isEmpty() ? null : b);
        }
        if (patch.headline() != null) {
            p.setHeadline(Check.text(patch.headline(), "headline", 0, 120));
        }
        if (patch.bio() != null) {
            p.setBio(Check.text(patch.bio(), "bio", 0, 2000));
        }
        if (patch.avatarUrl() != null) {
            p.setAvatarUrl(Check.url(patch.avatarUrl(), "avatarUrl"));
        }
        if (patch.coverUrl() != null) {
            p.setCoverUrl(Check.url(patch.coverUrl(), "coverUrl"));
        }
        if (patch.neighborhood() != null && "shop".equals(p.getWorkMode())) {
            String n = Check.text(patch.neighborhood(), "neighborhood", 0, 60);
            p.setNeighborhood(n.isEmpty() ? null : n);
        }
        if (patch.languages() != null) {
            p.setLanguages(new ArrayList<>(Check.strings(patch.languages(), "languages", 10, 30)));
        }
        if (patch.gear() != null) {
            if (patch.gear().size() > 30) {
                throw ApiException.validation("You can list up to 30 pieces of equipment.", "gear", "Max 30");
            }
            List<GearItem> gear = new ArrayList<>();
            for (GearItem g : patch.gear()) {
                Check.oneOf(g.kind(), "gear", GEAR_KINDS.toArray(new String[0]));
                gear.add(new GearItem(g.kind(), Check.text(g.name(), "gear", 1, 60)));
            }
            p.setGear(gear);
        }
        if (patch.showGear() != null) {
            p.setShowGear(patch.showGear());
        }
        if (patch.specialties() != null) {
            Set<String> valid = settings.categoryIds();
            if (patch.specialties().size() > 12 || !valid.containsAll(patch.specialties())) {
                throw ApiException.validation("Choose up to 12 listed specialties.", "specialties", "Unknown specialty");
            }
            p.setSpecialties(new ArrayList<>(new java.util.LinkedHashSet<>(patch.specialties())));
        }
        if (patch.yearsExperience() != null) {
            Check.range(patch.yearsExperience(), "yearsExperience", 0, 80);
            p.setYearsExperience(patch.yearsExperience());
        }
        if (patch.responseTimeHours() != null) {
            Check.range(patch.responseTimeHours(), "responseTimeHours", 1, 168);
            p.setResponseTimeHours(patch.responseTimeHours());
        }
        CancellationPolicy cp = patch.cancellationPolicy();
        if (cp != null) {
            Check.range(cp.freeCancelHours(), "freeCancelHours", 0, 720);
            Check.range(cp.partialRefundHours(), "partialRefundHours", 0, 720);
            Check.range(cp.partialRefundPercent(), "partialRefundPercent", 0, 100);
            if (cp.partialRefundHours() > cp.freeCancelHours()) {
                throw ApiException.validation("The partial-refund window cannot start earlier than the free-cancellation window.", "partialRefundHours", "Must not exceed free cancellation hours");
            }
            p.setCancellationPolicy(new CancellationPolicy(cp.freeCancelHours(), cp.partialRefundPercent(), cp.partialRefundHours(), Check.text(cp.summary(), "summary", 0, 300)));
        }
        if (patch.policies() != null) {
            p.setPolicies(new ArrayList<>(Check.strings(patch.policies(), "policies", 10, 300)));
        }
        if (patch.phone() != null) {
            p.setPhone(Check.text(patch.phone(), "phone", 0, 30));
        }
        if (patch.onboardingStep() != null && patch.onboardingStep() >= 1 && patch.onboardingStep() <= 5 && patch.onboardingStep() > p.getOnboardingStep()) {
            p.setOnboardingStep(patch.onboardingStep());
        }
        profiles.save(p);
        Update u = new Update().set("name", p.getDisplayName());
        if (patch.avatarUrl() != null) {
            u.set("avatarUrl", p.getAvatarUrl());
        }
        if (p.getPhone() != null && !p.getPhone().isBlank()) {
            u.set("phone", p.getPhone());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(username)), u, User.class);
        return p;
    }

    /** Kept for older clients. There is no review step any more: a photographer's profile is live from sign-up, so this just makes sure of that. */
    public PhotographerProfile submit(String username) {
        PhotographerProfile p = mine(username);
        if (!"approved".equals(p.getApproval())) {
            p.setApproval("approved");
        }
        p.setOnboardingStep(5);
        return profiles.save(p);
    }

    /** freelancer: only a known city + radius (the server derives an approximate point). shop: exact coordinates are required and published. */
    public PhotographerProfile setLocation(String username, String workMode, String cityIn, Integer radiusKm, GeoPoint point, String shopAddress) {
        PhotographerProfile p = mine(username);
        Check.oneOf(workMode, "workMode", "freelancer", "shop");
        String city = Geo.canonicalCity(cityIn);
        if (city == null) {
            throw ApiException.validation("Choose a city from the list.", "city", "Unknown city");
        }
        if (radiusKm == null || radiusKm < 0 || radiusKm > 300) {
            throw ApiException.validation("Invalid travel distance.", "radiusKm", "0-300");
        }
        if ("shop".equals(workMode)) {
            if (point == null || !Double.isFinite(point.lat()) || !Double.isFinite(point.lng()) || Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180) {
                throw ApiException.validation("A shop needs exact map coordinates.", "point", "Required");
            }
            p.setPublicLocation(new GeoPoint(Math.round(point.lat() * 1e6) / 1e6, Math.round(point.lng() * 1e6) / 1e6));
            String addr = shopAddress == null ? "" : shopAddress.trim();
            p.setShopAddress(addr.isEmpty() ? null : Check.text(addr, "shopAddress", 0, 160));
        } else {
            p.setPublicLocation(Geo.approximate(Geo.CITY_COORDS.get(city), p.getId()));
            p.setShopAddress(null);
            p.setNeighborhood(null);
        }
        p.setWorkMode(workMode);
        p.setCity(city);
        p.setServiceRadiusKm(radiusKm);
        profiles.save(p);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(username)), new Update().set("city", city), User.class);
        return p;
    }

    // ------------------------------------------------------------------ services & packages

    public ServiceOffer saveService(String username, ServiceOffer in) {
        PhotographerProfile me = mine(username);
        ServiceOffer s;
        if (in.getId() != null && !in.getId().isBlank()) {
            s = services.findById(in.getId()).filter(x -> x.getPhotographerId().equals(me.getId())).orElseThrow(() -> ApiException.notFound("Service not found"));
        } else {
            s = new ServiceOffer();
            s.setId(UUID.randomUUID().toString());
            s.setPhotographerId(me.getId());
            s.setCategoryId(me.getSpecialties().isEmpty() ? "portrait" : me.getSpecialties().get(0));
            if (in.getTitle() == null) {
                throw ApiException.validation("A service needs a title.", "title", "Required");
            }
        }
        if (in.getTitle() != null) {
            s.setTitle(Check.text(in.getTitle(), "title", 3, 120));
        }
        if (in.getDescription() != null) {
            s.setDescription(Check.text(in.getDescription(), "description", 0, 4000));
        }
        if (in.getCategoryId() != null) {
            if (!settings.categoryIds().contains(in.getCategoryId())) {
                throw ApiException.validation("Unknown category.", "categoryId", "Unknown category");
            }
            s.setCategoryId(in.getCategoryId());
        }
        if (in.getCoverImage() != null) {
            s.setCoverImage(Check.url(in.getCoverImage(), "coverImage"));
        }
        if (in.getGallery() != null) {
            if (in.getGallery().size() > 20) {
                throw ApiException.validation("Up to 20 gallery images.", "gallery", "Max 20");
            }
            s.setGallery(new ArrayList<>(in.getGallery().stream().map(g -> Check.url(g, "gallery")).toList()));
        }
        if (in.getPricingModel() != null) {
            Check.oneOf(in.getPricingModel(), "pricingModel", "fixed", "hourly", "starting", "quote");
            s.setPricingModel(in.getPricingModel());
        }
        if (in.getBasePrice() != null) {
            Check.range(in.getBasePrice(), "basePrice", 0, 10_000_000);
        }
        s.setBasePrice("quote".equals(s.getPricingModel()) ? null : in.getBasePrice() != null ? in.getBasePrice() : s.getBasePrice());
        if (in.getDurationMinutes() != null) {
            Check.range(in.getDurationMinutes(), "durationMinutes", 15, 1440);
            s.setDurationMinutes(in.getDurationMinutes());
        }
        if (in.getDeliverables() != null) {
            s.setDeliverables(new ArrayList<>(Check.strings(in.getDeliverables(), "deliverables", 20, 120)));
        }
        if (in.getEditedPhotos() != null) {
            Check.range(in.getEditedPhotos(), "editedPhotos", 0, 10000);
            s.setEditedPhotos(in.getEditedPhotos());
        }
        if (in.getDeliveryDays() != null) {
            Check.range(in.getDeliveryDays(), "deliveryDays", 0, 365);
            s.setDeliveryDays(in.getDeliveryDays());
        }
        if (in.getTravelRules() != null) {
            s.setTravelRules(Check.text(in.getTravelRules(), "travelRules", 0, 500));
        }
        if (in.getAddOns() != null) {
            if (in.getAddOns().size() > 20) {
                throw ApiException.validation("Up to 20 add-ons.", "addOns", "Max 20");
            }
            List<AddOn> addOns = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (AddOn a : in.getAddOns()) {
                Check.range(a.price(), "addOnPrice", 0, 10_000_000);
                String id = a.id() == null || a.id().isBlank() || !ids.add(a.id()) ? UUID.randomUUID().toString() : a.id();
                ids.add(id);
                addOns.add(new AddOn(id, Check.text(a.name(), "addOnName", 1, 80), a.price(), a.description() == null ? "" : Check.text(a.description(), "addOnDescription", 0, 300)));
            }
            s.setAddOns(addOns);
        }
        if (in.getStatus() != null) {
            Check.oneOf(in.getStatus(), "status", "draft", "published", "archived");
            s.setStatus(in.getStatus());
        }
        s.setUpdatedAt(Instant.now());
        return services.save(s);
    }

    public ServiceOffer setServiceStatus(String username, String id, String status) {
        Check.oneOf(status, "status", "draft", "published", "archived");
        ServiceOffer s = services.findById(id).filter(x -> x.getPhotographerId().equals(username)).orElseThrow(() -> ApiException.notFound("Service not found"));
        s.setStatus(status);
        s.setUpdatedAt(Instant.now());
        return services.save(s);
    }

    public ServicePackage savePackage(String username, ServicePackage in) {
        ServiceOffer svc = services.findById(in.getServiceId() == null ? "" : in.getServiceId()).filter(x -> x.getPhotographerId().equals(username))
                .orElseThrow(() -> ApiException.notFound("Service not found"));
        ServicePackage k;
        if (in.getId() != null && !in.getId().isBlank()) {
            k = packages.findById(in.getId()).filter(x -> x.getPhotographerId().equals(username) && x.getServiceId().equals(svc.getId()))
                    .orElseThrow(() -> ApiException.notFound("Package not found"));
        } else {
            k = new ServicePackage();
            k.setId(UUID.randomUUID().toString());
            k.setServiceId(svc.getId());
            k.setPhotographerId(username);
        }
        if (in.getName() != null) {
            k.setName(Check.text(in.getName(), "name", 2, 80));
        }
        if (in.getTier() != null) {
            Check.oneOf(in.getTier(), "tier", "bronze", "silver", "gold", "platinum");
            k.setTier(in.getTier());
        }
        if (in.getPrice() != null) {
            Check.range(in.getPrice(), "price", 0, 10_000_000);
        }
        k.setPrice(in.getPrice() != null || in.getId() == null ? in.getPrice() : k.getPrice());
        if (in.getDescription() != null) {
            k.setDescription(Check.text(in.getDescription(), "description", 0, 1000));
        }
        if (in.getDurationMinutes() != null) {
            Check.range(in.getDurationMinutes(), "durationMinutes", 15, 1440);
            k.setDurationMinutes(in.getDurationMinutes());
        }
        if (in.getEditedPhotos() != null) {
            Check.range(in.getEditedPhotos(), "editedPhotos", 0, 10000);
            k.setEditedPhotos(in.getEditedPhotos());
        }
        if (in.getLocations() != null) {
            Check.range(in.getLocations(), "locations", 1, 20);
            k.setLocations(in.getLocations());
        }
        if (in.getOutfitChanges() != null) {
            Check.range(in.getOutfitChanges(), "outfitChanges", 0, 50);
            k.setOutfitChanges(in.getOutfitChanges());
        }
        if (in.getDeliveryDays() != null) {
            Check.range(in.getDeliveryDays(), "deliveryDays", 0, 365);
            k.setDeliveryDays(in.getDeliveryDays());
        }
        if (in.getIncludes() != null) {
            k.setIncludes(new ArrayList<>(Check.strings(in.getIncludes(), "includes", 20, 120)));
        }
        if (in.getExtras() != null) {
            k.setExtras(new ArrayList<>(Check.strings(in.getExtras(), "extras", 20, 120)));
        }
        if (in.getTravelConditions() != null) {
            k.setTravelConditions(Check.text(in.getTravelConditions(), "travelConditions", 0, 500));
        }
        if (in.getBookingConditions() != null) {
            k.setBookingConditions(Check.text(in.getBookingConditions(), "bookingConditions", 0, 500));
        }
        if (in.getStatus() != null) {
            Check.oneOf(in.getStatus(), "status", "draft", "published", "archived");
            k.setStatus(in.getStatus());
        }
        return packages.save(k);
    }

    public ServicePackage setPackageStatus(String username, String id, String status) {
        Check.oneOf(status, "status", "draft", "published", "archived");
        ServicePackage k = packages.findById(id).filter(x -> x.getPhotographerId().equals(username)).orElseThrow(() -> ApiException.notFound("Package not found"));
        k.setStatus(status);
        return packages.save(k);
    }

    public void deletePackage(String username, String id) {
        ServicePackage k = packages.findById(id).filter(x -> x.getPhotographerId().equals(username)).orElseThrow(() -> ApiException.notFound("Package not found"));
        packages.delete(k);
    }

    // ------------------------------------------------------------------ portfolio

    public void savePortfolio(String username, List<PortfolioImage> images, List<Album> albums) {
        PhotographerProfile p = mine(username);
        if (images == null || albums == null || images.size() > 200 || albums.size() > 30) {
            throw ApiException.validation("A portfolio holds up to 200 images and 30 albums.");
        }
        Set<String> cats = settings.categoryIds();
        List<Album> cleanAlbums = new ArrayList<>();
        Set<String> albumIds = new HashSet<>();
        for (Album a : albums) {
            String id = a.id() == null || a.id().isBlank() ? UUID.randomUUID().toString() : a.id();
            albumIds.add(id);
            cleanAlbums.add(new Album(id, Check.text(a.name(), "albumName", 1, 60), a.order(), a.visible()));
        }
        List<PortfolioImage> clean = new ArrayList<>();
        boolean cover = false;
        for (PortfolioImage i : images) {
            String url = Check.url(i.url(), "url");
            if (url.isEmpty()) {
                throw ApiException.validation("An image needs an address.", "url", "Required");
            }
            if (i.categoryId() != null && !i.categoryId().isEmpty() && !cats.contains(i.categoryId())) {
                throw ApiException.validation("Unknown category on an image.", "categoryId", "Unknown category");
            }
            String album = i.albumId() != null && albumIds.contains(i.albumId()) ? i.albumId() : null;
            boolean isCover = i.isCover() && !cover;
            cover |= isCover;
            clean.add(new PortfolioImage(i.id() == null || i.id().isBlank() ? UUID.randomUUID().toString() : i.id(), url,
                    i.caption() == null ? "" : Check.text(i.caption(), "caption", 0, 200), i.categoryId(), album, i.order(), i.visible(), isCover));
        }
        p.setPortfolio(clean);
        p.setAlbums(cleanAlbums);
        profiles.save(p);
    }

    // ------------------------------------------------------------------ promo codes

    public List<PromoCode> promoCodes(String username) {
        return promos.findById(username).map(PromoCodeList::getCodes).orElse(List.of());
    }

    /** `uses` is owned by the server (counted when bookings are created); the client cannot reset it. */
    public List<PromoCode> savePromoCodes(String username, List<PromoCode> in) {
        if (in == null || in.size() > 50) {
            throw ApiException.validation("Up to 50 promo codes.");
        }
        List<PromoCode> current = promoCodes(username);
        Set<String> seen = new HashSet<>();
        List<PromoCode> out = new ArrayList<>();
        for (PromoCode c : in) {
            String code = c.code() == null ? "" : c.code().trim();
            if (!code.matches("^[A-Za-z0-9_-]{3,20}$")) {
                throw ApiException.validation("Codes use 3-20 letters, digits, dash or underscore.", "code", "Invalid code");
            }
            if (!seen.add(code.toUpperCase(Locale.ROOT))) {
                throw ApiException.validation("Duplicate code " + code, "code", "Duplicate");
            }
            if (c.percent() < 1 || c.percent() > 90) {
                throw ApiException.validation("Discount must be 1–90%.", "percent", "1-90");
            }
            if (c.maxUses() != null && c.maxUses() < 1) {
                throw ApiException.validation("Maximum uses must be at least 1.", "maxUses", "At least 1");
            }
            if (c.expiresAt() != null && !c.expiresAt().isBlank() && !Dates.isIsoDate(c.expiresAt())) {
                throw ApiException.validation("Invalid expiry date.", "expiresAt", "Use YYYY-MM-DD");
            }
            int uses = current.stream().filter(o -> o.code().equalsIgnoreCase(code)).mapToInt(PromoCode::uses).findFirst().orElse(0);
            out.add(new PromoCode(c.id() == null || c.id().isBlank() ? UUID.randomUUID().toString() : c.id(), code, c.percent(), c.active(),
                    c.expiresAt() == null || c.expiresAt().isBlank() ? null : c.expiresAt(), uses, c.maxUses()));
        }
        PromoCodeList l = promos.findById(username).orElseGet(() -> {
            PromoCodeList n = new PromoCodeList();
            n.setPhotographerId(username);
            return n;
        });
        l.setCodes(out);
        promos.save(l);
        return out;
    }

    // ------------------------------------------------------------------ automation

    public AutomationSettings automation(String username) {
        mine(username);
        return automation.findById(username).orElseGet(() -> {
            AutomationSettings a = new AutomationSettings();
            a.setPhotographerId(username);
            a.setTemplates(ProfileProvisioner.defaultTemplates());
            a.setUpdatedAt(Instant.now());
            return automation.save(a);
        });
    }

    public AutomationSettings saveAutomation(String username, AutomationSettings in) {
        AutomationSettings cur = automation(username);
        if (in.getReminders() == null || in.getAutoAccept() == null || in.getBookingRequestNotify() == null || in.getConfirmationMessage() == null || in.getFollowUp() == null
                || in.getReviewRequest() == null || in.getAutoExpire() == null || in.getPendingNudge() == null || in.getRepeatCustomerOffer() == null
                || in.getGalleryTracking() == null || in.getTemplates() == null) {
            throw ApiException.validation("Incomplete automation settings.");
        }
        if (in.getReminders().hoursBefore().size() > 6 || in.getReminders().hoursBefore().stream().anyMatch(h -> h == null || h < 1 || h > 336)) {
            throw ApiException.validation("Reminder times must be between 1 and 336 hours.", "hoursBefore", "1-336");
        }
        if (in.getAutoAccept().enabled() && in.getAutoAccept().minNoticeHours() < 12) {
            throw ApiException.validation("Auto-accept needs at least 12 hours of notice as a safeguard.", "minNoticeHours", "At least 12");
        }
        if (in.getAutoAccept().minNoticeHours() > 24 * 365 || (in.getAutoAccept().maxPriceMad() != null && in.getAutoAccept().maxPriceMad() < 0)) {
            throw ApiException.validation("Auto-accept limits are out of range.");
        }
        Check.range(in.getFollowUp().delayHours(), "delayHours", 0, 720);
        Check.range(in.getReviewRequest().delayHours(), "delayHours", 0, 720);
        Check.range(in.getAutoExpire().afterHours(), "afterHours", 1, 720);
        Check.range(in.getPendingNudge().afterHours(), "afterHours", 1, 720);
        Check.range(in.getRepeatCustomerOffer().percent(), "percent", 1, 90);
        if (in.getTemplates().size() > 20) {
            throw ApiException.validation("Up to 20 templates.");
        }
        List<MessageTemplate> templates = new ArrayList<>();
        for (MessageTemplate t : in.getTemplates()) {
            Check.oneOf(t.kind(), "kind", "message", "quotation");
            templates.add(new MessageTemplate(t.id() == null || t.id().isBlank() ? UUID.randomUUID().toString() : t.id(), Check.text(t.name(), "templateName", 1, 80),
                    Check.text(t.body(), "templateBody", 1, 2000), t.kind()));
        }
        cur.setBookingRequestNotify(in.getBookingRequestNotify());
        cur.setConfirmationMessage(in.getConfirmationMessage());
        cur.setReminders(in.getReminders());
        cur.setFollowUp(in.getFollowUp());
        cur.setReviewRequest(in.getReviewRequest());
        cur.setAutoExpire(in.getAutoExpire());
        cur.setPendingNudge(in.getPendingNudge());
        cur.setAutoAccept(in.getAutoAccept());
        cur.setRepeatCustomerOffer(in.getRepeatCustomerOffer());
        cur.setGalleryTracking(in.getGalleryTracking());
        cur.setTemplates(templates);
        cur.setUpdatedAt(Instant.now());
        return automation.save(cur);
    }
}
