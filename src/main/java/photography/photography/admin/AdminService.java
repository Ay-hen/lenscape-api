package photography.photography.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import photography.photography.account.UserDto;
import photography.photography.api.ApiException;
import photography.photography.api.Check;
import photography.photography.api.PlatformClock;
import photography.photography.model.Booking;
import photography.photography.model.Misc.Application;
import photography.photography.model.Misc.AuditEntry;
import photography.photography.model.Misc.ContentReport;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.User;
import photography.photography.model.Values.ApprovalEvent;
import photography.photography.model.Values.PageResult;
import photography.photography.model.Values.Series;
import photography.photography.model.Values.TimePoint;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.ApplicationRepo;
import photography.photography.respository.MiscRepos.ReportRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;
import photography.photography.respository.UserRepo;

@Service
public class AdminService {
    private final UserRepo users;
    private final PhotographerProfileRepo profiles;
    private final ServiceOfferRepo services;
    private final ServicePackageRepo packages;
    private final BookingRepo bookings;
    private final ApplicationRepo applications;
    private final ReportRepo reports;
    private final AuditService audit;
    private final SettingsService settings;
    private final Notifier notifier;
    private final PlatformClock clock;
    private final MongoTemplate mongo;

    public AdminService(UserRepo users, PhotographerProfileRepo profiles, ServiceOfferRepo services, ServicePackageRepo packages, BookingRepo bookings,
                        ApplicationRepo applications, ReportRepo reports, AuditService audit, SettingsService settings, Notifier notifier, PlatformClock clock,
                        MongoTemplate mongo) {
        this.users = users;
        this.profiles = profiles;
        this.services = services;
        this.packages = packages;
        this.bookings = bookings;
        this.applications = applications;
        this.reports = reports;
        this.audit = audit;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
        this.mongo = mongo;
    }

    private static boolean live(Booking b) {
        return "confirmed".equals(b.getStatus()) || "completed".equals(b.getStatus());
    }

    private String day(Instant i) {
        return i == null ? null : i.atZone(clock.zone()).toLocalDate().toString();
    }

    // ------------------------------------------------------------------ overview

    public record Overview(int totalCustomers, int totalPhotographers, int pendingApprovals, int activeServices, int publishedPackages, int totalBookings,
                           int upcomingBookings, double cancellationRate, double grossBookingValue, double platformRevenue, String currency, Series customerGrowth,
                           Series photographerGrowth, Series bookingTrend, List<TimePoint> topCategories, List<AuditEntry> recentActivity, long openReports,
                           long openDisputes) {
    }

    public Overview overview() {
        LocalDate today = clock.today();
        LocalDate from = today.minusDays(165);
        LocalDate to = today.plusDays(15);
        List<User> all = users.findAll();
        List<User> clients = all.stream().filter(u -> "client".equals(u.getRole())).toList();
        List<User> photographers = all.stream().filter(u -> "photographer".equals(u.getRole())).toList();
        List<PhotographerProfile> ps = profiles.findAll();
        List<Booking> bk = bookings.findAll();
        Map<String, String> catNames = settings.get().getCategories().stream().collect(Collectors.toMap(c -> c.id(), c -> c.name(), (a, b) -> a));
        Map<String, Integer> cats = new HashMap<>();
        bk.stream().filter(b -> !"rejected".equals(b.getStatus())).forEach(b -> cats.merge(b.getCategoryId(), 1, Integer::sum));
        List<TimePoint> top = cats.entrySet().stream().map(e -> new TimePoint(catNames.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .sorted(Comparator.comparingDouble(TimePoint::value).reversed()).limit(6).toList();
        long cancelled = bk.stream().filter(b -> "cancelled".equals(b.getStatus())).count();
        return new Overview(clients.size(), ps.size(), (int) ps.stream().filter(p -> "pending".equals(p.getApproval()) || "needs_info".equals(p.getApproval())).count(),
                (int) services.findAll().stream().filter(s -> "published".equals(s.getStatus()) && !"hidden".equals(s.getModeration())).count(),
                (int) packages.findAll().stream().filter(p -> "published".equals(p.getStatus())).count(), bk.size(),
                (int) bk.stream().filter(b -> b.getDate().compareTo(today.toString()) >= 0 && ("confirmed".equals(b.getStatus()) || "pending".equals(b.getStatus()))).count(),
                bk.isEmpty() ? 0 : (double) cancelled / bk.size(), bk.stream().filter(AdminService::live).mapToDouble(b -> b.getPricing().total()).sum(),
                bk.stream().filter(b -> "completed".equals(b.getStatus())).mapToDouble(b -> b.getPricing().platformFee()).sum(), "MAD",
                SeriesUtil.count("Customers", from, to, clients, u -> day(u.getCreatedAt())), SeriesUtil.count("Photographers", from, to, photographers, u -> day(u.getCreatedAt())),
                SeriesUtil.count("Bookings", from, to, bk, Booking::getDate), top, audit.recent(6), reports.countByState("open"),
                bk.stream().filter(b -> b.getDispute() != null && "open".equals(b.getDispute().state())).count());
    }

    // ------------------------------------------------------------------ users

    public PageResult<UserDto> users(String role, String status, String q, String sort, String dir, Integer pageIn, Integer sizeIn) {
        Map<String, String> approval = new HashMap<>();
        profiles.findAll().forEach(p -> approval.put(p.getId(), p.getApproval()));
        List<UserDto> list = new ArrayList<>(users.findAll().stream().map(u -> UserDto.of(u, "photographer".equals(u.getRole()) ? approval.getOrDefault(u.getUsername(), "draft") : null))
                .filter(u -> role == null || role.isEmpty() || "all".equals(role) || u.role().equals(role))
                .filter(u -> status == null || status.isEmpty() || "all".equals(status) || u.status().equals(status))
                .filter(u -> {
                    if (q == null || q.isBlank()) {
                        return true;
                    }
                    String t = q.toLowerCase();
                    return (u.name() + " " + u.email() + " " + u.username() + " " + (u.city() == null ? "" : u.city())).toLowerCase().contains(t);
                }).toList());
        Function<UserDto, String> key = switch (sort == null ? "name" : sort) {
            case "email" -> UserDto::email;
            case "username" -> UserDto::username;
            case "city" -> u -> u.city() == null ? "" : u.city();
            case "role" -> UserDto::role;
            case "status" -> UserDto::status;
            case "createdAt" -> u -> u.createdAt() == null ? "" : u.createdAt().toString();
            case "lastActiveAt" -> u -> u.lastActiveAt() == null ? "" : u.lastActiveAt().toString();
            default -> UserDto::name;
        };
        Comparator<UserDto> cmp = Comparator.comparing(key, String.CASE_INSENSITIVE_ORDER);
        list.sort("desc".equals(dir) ? cmp.reversed() : cmp);
        int page = pageIn == null || pageIn < 1 ? 1 : pageIn;
        int size = sizeIn == null ? 20 : Math.max(1, Math.min(sizeIn, 100));
        int from = Math.min((page - 1) * size, list.size());
        return new PageResult<>(list.subList(from, Math.min(from + size, list.size())), list.size(), page, size);
    }

    public UserDto setUserStatus(String id, String status, String reason, User admin) {
        Check.oneOf(status, "status", "active", "suspended");
        User u = users.findByUsername(id).orElseThrow(() -> ApiException.notFound("User not found"));
        if ("admin".equals(u.getRole())) {
            throw ApiException.forbidden("Admin accounts cannot be suspended here.");
        }
        boolean suspend = "suspended".equals(status);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("suspended", suspend), User.class);
        u.setSuspended(suspend);
        notifier.notify(id, NotificationType.SYSTEM, suspend ? "Account suspended" : "Account restored",
                suspend ? "Your account has been suspended by an administrator." : "Your account is active again.", "/", "account:status");
        audit.log(admin.getName(), suspend ? "suspended account" : "reactivated account", u.getName(), reason);
        String approval = "photographer".equals(u.getRole()) ? profiles.findById(id).map(PhotographerProfile::getApproval).orElse("draft") : null;
        return UserDto.of(u, approval);
    }

    // ------------------------------------------------------------------ applications

    public List<Application> applications() {
        return applications.findAllByOrderBySubmittedAtDesc();
    }

    public Application decide(String pid, String action, String note, User admin) {
        Check.oneOf(action, "action", "approved", "rejected", "info_requested", "identity_verified");
        String n = note == null ? "" : Check.text(note, "note", 0, 1000);
        Application app = applications.findById(pid).orElseThrow(() -> ApiException.notFound("Application not found"));
        PhotographerProfile p = profiles.findById(pid).orElseThrow(() -> ApiException.notFound("Application not found"));
        if (("rejected".equals(action) || "info_requested".equals(action)) && n.length() < 5) {
            throw ApiException.validation("Please record a reason.", "note", "Required");
        }
        if ("identity_verified".equals(action) && !"approved".equals(p.getApproval())) {
            throw ApiException.conflict("Approve the profile before verifying identity.");
        }
        if (!"identity_verified".equals(action) && !List.of("pending", "needs_info").contains(p.getApproval())) {
            throw ApiException.conflict("This application is not waiting for a decision.");
        }
        List<ApprovalEvent> h = new ArrayList<>(app.getHistory());
        h.add(new ApprovalEvent(Instant.now(), admin.getName(), action, n.isEmpty() ? null : n));
        app.setHistory(h);
        switch (action) {
            case "approved" -> {
                p.setApproval("approved");
                app.setStatus("approved");
            }
            case "rejected" -> {
                p.setApproval("rejected");
                app.setStatus("rejected");
            }
            case "info_requested" -> {
                p.setApproval("needs_info");
                app.setStatus("needs_info");
            }
            default -> {
                p.setIdentityVerified(true);
                app.setIdentityVerified(true);
            }
        }
        profiles.save(p);
        applications.save(app);
        notifier.notify(pid, NotificationType.APPROVAL, "Application " + action.replace('_', ' '), n.isEmpty() ? "Your application was updated." : n, "/studio", "approval:" + pid);
        audit.log(admin.getName(), action.replace('_', ' ') + " photographer", p.getDisplayName(), n);
        return app;
    }

    // ------------------------------------------------------------------ moderation & reports

    public List<ServiceOffer> allServices() {
        return services.findAll();
    }

    public List<ServicePackage> allPackages() {
        return packages.findAll();
    }

    public ServiceOffer moderateService(String id, String state, Boolean featured, User admin) {
        Check.oneOf(state, "state", "ok", "reported", "hidden");
        ServiceOffer s = services.findById(id).orElseThrow(() -> ApiException.notFound("Service not found"));
        s.setModeration(state);
        if (featured != null) {
            s.setFeatured(featured);
        }
        services.save(s);
        audit.log(admin.getName(), "hidden".equals(state) ? "hid listing" : featured != null ? "updated featured listing" : "restored listing", s.getTitle(), null);
        return s;
    }

    public List<ContentReport> reports() {
        return reports.findAllByOrderByAtDesc();
    }

    public ContentReport resolveReport(String id, String state, User admin) {
        Check.oneOf(state, "state", "open", "dismissed", "actioned");
        ContentReport r = reports.findById(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        r.setState(state);
        reports.save(r);
        audit.log(admin.getName(), "report " + state, r.getTargetLabel(), null);
        return r;
    }

    public void report(String targetType, String targetId, String targetLabel, String reason, User by) {
        Check.oneOf(targetType, "targetType", "profile", "service", "review", "account");
        ContentReport r = new ContentReport();
        r.setId(UUID.randomUUID().toString());
        r.setTargetType(targetType);
        r.setTargetId(Check.text(targetId, "targetId", 1, 100));
        r.setTargetLabel(Check.text(targetLabel, "targetLabel", 1, 200));
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 10 || why.length() > 2000) {
            throw ApiException.validation("Please describe the problem (10-2000 characters).", "reason", "10-2000 characters");
        }
        r.setReason(why);
        r.setReportedBy(by.getName());
        r.setAt(Instant.now());
        reports.insert(r);
    }

    public List<AuditEntry> audit() {
        return audit.recent(500);
    }

    public PlatformSettings settings() {
        return settings.get();
    }

    public PlatformSettings saveSettings(PlatformSettings in, User admin) {
        PlatformSettings saved = settings.save(in);
        audit.log(admin.getName(), "updated platform settings", "Settings", null);
        return saved;
    }
}
