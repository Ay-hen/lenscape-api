package photography.photography.catalog;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import photography.photography.admin.SettingsService;
import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.catalog.CatalogDtos.PackageHighlight;
import photography.photography.catalog.CatalogDtos.PhotographerSummary;
import photography.photography.catalog.CatalogDtos.SearchQuery;
import photography.photography.domain.AvailabilityEngine;
import photography.photography.domain.Geo;
import photography.photography.domain.PricingEngine;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.Misc.ViewStat;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.Review;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.User;
import photography.photography.model.Values.BusyInterval;
import photography.photography.model.Values.CategoryDef;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.PageResult;
import photography.photography.respository.AvailabilityRepo;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ReviewRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;
import photography.photography.respository.UserRepo;

/** Public marketplace reads: search, profiles, services, packages, reviews. Writes by owners live in WorkspaceService. */
@Service
public class CatalogService {
    private static final List<String> LIVE = List.of("pending", "confirmed");

    private final PhotographerProfileRepo profiles;
    private final ServiceOfferRepo services;
    private final ServicePackageRepo packages;
    private final AvailabilityRepo availability;
    private final BookingRepo bookings;
    private final ReviewRepo reviews;
    private final UserRepo users;
    private final SettingsService settings;
    private final PlatformClock clock;
    private final MongoTemplate mongo;

    public CatalogService(PhotographerProfileRepo profiles, ServiceOfferRepo services, ServicePackageRepo packages, AvailabilityRepo availability,
                          BookingRepo bookings, ReviewRepo reviews, UserRepo users, SettingsService settings, PlatformClock clock, MongoTemplate mongo) {
        this.profiles = profiles;
        this.services = services;
        this.packages = packages;
        this.availability = availability;
        this.bookings = bookings;
        this.reviews = reviews;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
        this.mongo = mongo;
    }

    // ------------------------------------------------------------------ helpers

    private record Ctx(Map<String, List<ServiceOffer>> svc, Map<String, List<ServicePackage>> pk, Map<String, AvailabilityRules> rules,
                       Map<String, List<BusyInterval>> busy) {
    }

    private Ctx load(List<PhotographerProfile> ps) {
        Set<String> ids = ps.stream().map(PhotographerProfile::getId).collect(Collectors.toSet());
        Map<String, List<ServiceOffer>> svc = services.findByPhotographerIdIn(ids).stream().collect(Collectors.groupingBy(ServiceOffer::getPhotographerId));
        Map<String, List<ServicePackage>> pk = packages.findByPhotographerIdIn(ids).stream().collect(Collectors.groupingBy(ServicePackage::getPhotographerId));
        Map<String, AvailabilityRules> rules = new HashMap<>();
        availability.findAllById(ids).forEach(r -> rules.put(r.getPhotographerId(), r));
        Map<String, List<BusyInterval>> busy = new HashMap<>();
        bookings.findByStatusInAndDateGreaterThanEqual(LIVE, clock.today().toString()).forEach(
                b -> busy.computeIfAbsent(b.getPhotographerId(), k -> new ArrayList<>()).add(new BusyInterval(b.getDate(), b.getStartTime(), b.getEndTime())));
        return new Ctx(svc, pk, rules, busy);
    }

    private Set<String> suspended() {
        return users.findBySuspended(true).stream().map(User::getUsername).collect(Collectors.toSet());
    }

    private boolean isPublic(PhotographerProfile p, Set<String> suspended) {
        return "approved".equals(p.getApproval()) && !suspended.contains(p.getId());
    }

    private static int minDuration(List<ServicePackage> pk) {
        int m = 120;
        for (ServicePackage k : pk) {
            if ("published".equals(k.getStatus()) && k.getDurationMinutes() != null) {
                m = Math.min(m, k.getDurationMinutes());
            }
        }
        return m;
    }

    private PhotographerSummary summarize(PhotographerProfile p, Ctx c, GeoPoint center, String date, boolean withNext) {
        List<ServiceOffer> svc = c.svc.getOrDefault(p.getId(), List.of());
        List<ServicePackage> pk = c.pk.getOrDefault(p.getId(), List.of());
        AvailabilityRules rules = c.rules.get(p.getId());
        List<BusyInterval> busy = c.busy.getOrDefault(p.getId(), List.of());
        int dur = minDuration(pk);
        LocalDateTime now = clock.now();
        LocalDate today = clock.today();
        List<String> tiers = pk.stream().filter(x -> "published".equals(x.getStatus())).map(ServicePackage::getTier).distinct().toList();
        Boolean availableOn = date != null && rules != null ? AvailabilityEngine.hasAnySlot(rules, date, dur, busy, now, today) : null;
        String next = withNext && rules != null ? AvailabilityEngine.nextAvailableDate(rules, dur, busy, now, today) : null;
        Double dist = center != null ? Math.round(Geo.haversineKm(center, p.getPublicLocation()) * 10) / 10.0 : null;
        return new PhotographerSummary(p.getId(), p.getDisplayName(), p.getAvatarUrl(), p.getCoverUrl(), p.getCity(), p.getWorkMode(), p.getHeadline(),
                p.getRating(), p.getReviewCount(), p.getSpecialties(), p.getYearsExperience(), PricingEngine.startingPrice(svc, pk), tiers,
                p.getPublicLocation(), dist, availableOn, next, p.isIdentityVerified(), p.isFeatured(),
                p.getProfileViews() + p.getFavoritesCount() * 10 + p.getCompletedBookings() * 5, p.getServiceRadiusKm());
    }

    private static boolean owner(Principal me, String photographerId) {
        return me != null && me.getName().equals(photographerId);
    }

    private boolean admin(Principal me) {
        return me != null && users.findByUsername(me.getName()).map(u -> "admin".equals(u.getRole())).orElse(false);
    }

    // ------------------------------------------------------------------ reads

    public List<CategoryDef> categories() {
        return settings.get().getCategories();
    }

    public PageResult<PhotographerSummary> search(SearchQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int pageSize = q.pageSize() == null ? 12 : Math.max(1, Math.min(q.pageSize(), 200));
        String sort = q.sort() == null ? "recommended" : q.sort();
        if (q.availableOn() != null && !photography.photography.domain.Dates.isIsoDate(q.availableOn())) {
            throw ApiException.validation("Invalid date.", "availableOn", "Use YYYY-MM-DD");
        }
        GeoPoint center = q.lat() != null && q.lng() != null && Math.abs(q.lat()) <= 90 && Math.abs(q.lng()) <= 180 ? new GeoPoint(q.lat(), q.lng()) : null;
        Set<String> susp = suspended();
        List<PhotographerProfile> pool = profiles.findByApproval("approved").stream().filter(p -> !susp.contains(p.getId())).toList();
        Ctx c = load(pool);
        Map<String, String> catNames = settings.get().getCategories().stream().collect(Collectors.toMap(CategoryDef::id, CategoryDef::name, (a, b) -> a));
        String text = q.q() == null ? null : q.q().trim().toLowerCase();
        String city = q.city() == null ? null : q.city().trim().toLowerCase();
        List<PhotographerSummary> list = new ArrayList<>();
        for (PhotographerProfile p : pool) {
            if (text != null && !text.isEmpty()) {
                String hay = (p.getDisplayName() + " " + p.getCity() + " " + p.getHeadline() + " " + (p.getNeighborhood() == null ? "" : p.getNeighborhood()) + " "
                        + p.getSpecialties().stream().map(s -> s + " " + catNames.getOrDefault(s, "")).collect(Collectors.joining(" "))).toLowerCase();
                if (!hay.contains(text)) {
                    continue;
                }
            }
            if (city != null && !city.isEmpty() && !p.getCity().toLowerCase().contains(city)) {
                continue;
            }
            if (q.categoryId() != null && !q.categoryId().isEmpty() && !p.getSpecialties().contains(q.categoryId())) {
                continue;
            }
            if (q.minRating() != null && p.getRating() < q.minRating()) {
                continue;
            }
            if (q.minExperience() != null && p.getYearsExperience() < q.minExperience()) {
                continue;
            }
            if (Boolean.TRUE.equals(q.verifiedOnly()) && !p.isIdentityVerified()) {
                continue;
            }
            PhotographerSummary s = summarize(p, c, center, q.availableOn(), false);
            if (q.minPrice() != null || q.maxPrice() != null) {
                Double a = s.startingPrice().amount();
                if (a == null || a < (q.minPrice() == null ? 0 : q.minPrice()) || a > (q.maxPrice() == null ? Double.MAX_VALUE : q.maxPrice())) {
                    continue;
                }
            }
            if (q.availableOn() != null && !Boolean.TRUE.equals(s.availableOnDate())) {
                continue;
            }
            if (q.tier() != null && !q.tier().isEmpty() && !s.tiers().contains(q.tier())) {
                continue;
            }
            if (center != null && q.radiusKm() != null && q.radiusKm() > 0 && s.distanceKm() != null && s.distanceKm() > q.radiusKm()) {
                continue;
            }
            list.add(s);
        }
        list.sort(comparator(sort));
        int from = Math.min((page - 1) * pageSize, list.size());
        List<PhotographerSummary> slice = list.subList(from, Math.min(from + pageSize, list.size()));
        Map<String, PhotographerProfile> byId = pool.stream().collect(Collectors.toMap(PhotographerProfile::getId, p -> p));
        List<PhotographerSummary> withNext = new ArrayList<>();
        for (PhotographerSummary s : slice) { // the "next available" scan is only worth doing for the visible page
            withNext.add(summarize(byId.get(s.id()), c, center, q.availableOn(), true));
        }
        return new PageResult<>(withNext, list.size(), page, pageSize);
    }

    private static Comparator<PhotographerSummary> comparator(String sort) {
        Comparator<PhotographerSummary> recommended = Comparator.<PhotographerSummary>comparingInt(s -> s.featured() ? 0 : 1)
                .thenComparing(Comparator.comparingDouble((PhotographerSummary s) -> s.rating() * Math.log(s.reviewCount() + 2)).reversed());
        return switch (sort) {
            case "nearest" -> Comparator.comparingDouble(s -> s.distanceKm() == null ? 1e9 : s.distanceKm());
            case "price_asc" -> Comparator.comparingDouble(s -> s.startingPrice().amount() == null ? Double.MAX_VALUE : s.startingPrice().amount());
            case "price_desc" -> Comparator.<PhotographerSummary>comparingDouble(s -> s.startingPrice().amount() == null ? -1 : s.startingPrice().amount()).reversed();
            case "rating" -> Comparator.comparingDouble(PhotographerSummary::rating).reversed().thenComparing(Comparator.comparingInt(PhotographerSummary::reviewCount).reversed());
            case "popular" -> Comparator.comparingInt(PhotographerSummary::popularity).reversed();
            default -> recommended;
        };
    }

    public PhotographerProfile photographer(String id, Principal me) {
        PhotographerProfile p = profiles.findById(id).orElseThrow(() -> ApiException.notFound("Photographer not found"));
        boolean mine = owner(me, id);
        boolean adm = !mine && admin(me);
        if (!mine && !adm && !isPublic(p, suspended())) {
            throw ApiException.notFound("This profile is not public.");
        }
        if (!mine) {
            p.setPortfolio(p.getPortfolio().stream().filter(i -> i.visible()).toList());
        }
        if (!mine && !adm) {
            p.setPhone(null); // contact details are not part of the public profile
        }
        return p;
    }

    public List<PhotographerSummary> similar(String id) {
        PhotographerProfile me = profiles.findById(id).orElse(null);
        if (me == null) {
            return List.of();
        }
        Set<String> susp = suspended();
        List<PhotographerProfile> cand = profiles.findByApproval("approved").stream().filter(p -> !p.getId().equals(id) && !susp.contains(p.getId())
                && p.getSpecialties().stream().anyMatch(me.getSpecialties()::contains)).sorted(Comparator
                .comparingInt((PhotographerProfile p) -> p.getCity().equals(me.getCity()) ? 0 : 1).thenComparing(Comparator.comparingDouble(PhotographerProfile::getRating).reversed()))
                .limit(4).toList();
        Ctx c = load(cand);
        return cand.stream().map(p -> summarize(p, c, null, null, true)).toList();
    }

    public List<PhotographerSummary> summaries(List<String> ids) {
        List<String> clean = ids.stream().distinct().limit(50).toList();
        Set<String> susp = suspended();
        List<PhotographerProfile> ps = new ArrayList<>();
        for (String id : clean) {
            profiles.findById(id).filter(p -> isPublic(p, susp)).ifPresent(ps::add);
        }
        Ctx c = load(ps);
        return ps.stream().map(p -> summarize(p, c, null, null, true)).toList();
    }

    public List<ServiceOffer> services(String pid, boolean includeUnpublished, Principal me) {
        boolean all = includeUnpublished && (owner(me, pid) || admin(me));
        List<ServiceOffer> list = services.findByPhotographerId(pid);
        if (!all && !publicProfile(pid)) {
            return List.of();
        }
        return all ? list : list.stream().filter(s -> "published".equals(s.getStatus()) && !"hidden".equals(s.getModeration())).toList();
    }

    public List<ServicePackage> packages(String pid, boolean includeUnpublished, Principal me) {
        boolean all = includeUnpublished && (owner(me, pid) || admin(me));
        if (!all && !publicProfile(pid)) {
            return List.of();
        }
        List<ServicePackage> list = packages.findByPhotographerId(pid);
        if (all) {
            return list;
        }
        Set<String> hidden = services.findByPhotographerId(pid).stream().filter(s -> "hidden".equals(s.getModeration()) || !"published".equals(s.getStatus()))
                .map(ServiceOffer::getId).collect(Collectors.toSet());
        return list.stream().filter(p -> "published".equals(p.getStatus()) && !hidden.contains(p.getServiceId())).toList();
    }

    private boolean publicProfile(String pid) {
        return profiles.findById(pid).map(p -> isPublic(p, suspended())).orElse(false);
    }

    public List<Review> reviews(String pid) {
        return reviews.findByPhotographerIdOrderByCreatedAtDesc(pid);
    }

    /** Latest highly rated reviews of public photographers, for the home page. Empty until real customers have reviewed something. */
    public List<Review> featuredReviews(int limit) {
        int max = Math.max(1, Math.min(limit, 12));
        Set<String> susp = suspended();
        Set<String> approved = profiles.findByApproval("approved").stream().map(PhotographerProfile::getId).filter(id -> !susp.contains(id)).collect(Collectors.toSet());
        return reviews.findByRatingGreaterThanEqualOrderByCreatedAtDesc(4, org.springframework.data.domain.PageRequest.of(0, max * 4)).stream()
                .filter(r -> approved.contains(r.getPhotographerId())).limit(max).toList();
    }

    public void recordView(String pid) {
        PhotographerProfile p = profiles.findById(pid).orElse(null);
        if (p == null || !"approved".equals(p.getApproval())) {
            return;
        }
        profiles.incrementViews(pid);
        String day = clock.today().toString();
        mongo.upsert(Query.query(Criteria.where("_id").is(pid + "|" + day)),
                new Update().inc("count", 1).setOnInsert("photographerId", pid).setOnInsert("day", day), ViewStat.class);
    }

    public List<PackageHighlight> bestValue(int limit) {
        int max = Math.max(1, Math.min(limit, 12));
        Set<String> susp = suspended();
        Map<String, PhotographerProfile> approved = profiles.findByApproval("approved").stream().filter(p -> !susp.contains(p.getId()))
                .collect(Collectors.toMap(PhotographerProfile::getId, p -> p));
        Map<String, ServiceOffer> svc = new HashMap<>();
        services.findByStatus("published").stream().filter(s -> !"hidden".equals(s.getModeration()) && !"hourly".equals(s.getPricingModel())
                && !"quote".equals(s.getPricingModel()) && approved.containsKey(s.getPhotographerId())).forEach(s -> svc.put(s.getId(), s));
        record Scored(ServicePackage k, ServiceOffer s, PhotographerProfile p, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (ServicePackage k : packages.findAll()) {
            ServiceOffer s = svc.get(k.getServiceId());
            if (s == null || !"published".equals(k.getStatus()) || k.getPrice() == null || k.getPrice() <= 0) {
                continue;
            }
            PhotographerProfile p = approved.get(k.getPhotographerId());
            double score = p.getRating() * (k.getEditedPhotos() == null ? 0 : k.getEditedPhotos()) / k.getPrice();
            if (score > 0) {
                scored.add(new Scored(k, s, p, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        Set<String> seen = new LinkedHashSet<>();
        List<Scored> pick = new ArrayList<>();
        for (Scored x : scored) {
            if (seen.add(x.p().getId())) {
                pick.add(x);
            }
            if (pick.size() >= max) {
                break;
            }
        }
        Ctx c = load(pick.stream().map(Scored::p).toList());
        List<PackageHighlight> out = new ArrayList<>();
        for (Scored x : pick) {
            out.add(new PackageHighlight(x.k(), x.s().getTitle(), x.s().getPricingModel(), summarize(x.p(), c, null, null, true)));
        }
        return out;
    }

    public String banner() {
        PlatformSettings s = settings.get();
        return s.getMaintenanceBanner() == null ? "" : s.getMaintenanceBanner();
    }

    public List<photography.photography.model.Values.FaqItem> faqs() {
        return settings.get().getFaqs();
    }

    /** Used by other services that need the set of valid specialties. */
    public Set<String> categoryIds() {
        return new HashSet<>(settings.categoryIds());
    }
}
