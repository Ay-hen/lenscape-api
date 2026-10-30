package photography.photography.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import photography.photography.admin.AuditService;
import photography.photography.admin.SettingsService;
import photography.photography.api.ApiException;
import photography.photography.api.Check;
import photography.photography.api.KeyedLock;
import photography.photography.api.PlatformClock;
import photography.photography.booking.BookingDtos.BookingQuery;
import photography.photography.booking.BookingDtos.CreateBookingRequest;
import photography.photography.booking.BookingDtos.QuoteRequest;
import photography.photography.catalog.AvailabilityService;
import photography.photography.domain.AvailabilityEngine;
import photography.photography.domain.Dates;
import photography.photography.domain.PricingEngine;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Booking;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Misc.Counter;
import photography.photography.model.Misc.PromoCodeList;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.User;
import photography.photography.model.Values.AddOnLine;
import photography.photography.model.Values.BookingEvent;
import photography.photography.model.Values.BookingLocation;
import photography.photography.model.Values.Cancellation;
import photography.photography.model.Values.Dispute;
import photography.photography.model.Values.PageResult;
import photography.photography.model.Values.PriceBreakdown;
import photography.photography.model.Values.PromoCode;
import photography.photography.model.Values.RescheduleRequest;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;
import photography.photography.respository.MiscRepos.PromoRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;
import photography.photography.respository.UserRepo;

/**
 * Booking rules. The server is authoritative for price, slot availability, who may change what, and refunds.
 * Slot-conflict checks and writes for one photographer run inside a per-photographer lock (single instance).
 */
@Service
public class BookingService {
    private static final Set<String> LIVE = Set.of("pending", "confirmed");

    private final BookingRepo bookings;
    private final ServiceOfferRepo services;
    private final ServicePackageRepo packages;
    private final PhotographerProfileRepo profiles;
    private final PromoRepo promos;
    private final AutomationRepo automation;
    private final UserRepo users;
    private final AvailabilityService availability;
    private final SettingsService settings;
    private final AuditService audit;
    private final Notifier notifier;
    private final PlatformClock clock;
    private final KeyedLock lock;
    private final MongoTemplate mongo;

    public BookingService(BookingRepo bookings, ServiceOfferRepo services, ServicePackageRepo packages, PhotographerProfileRepo profiles, PromoRepo promos,
                          AutomationRepo automation, UserRepo users, AvailabilityService availability, SettingsService settings, AuditService audit,
                          Notifier notifier, PlatformClock clock, KeyedLock lock, MongoTemplate mongo) {
        this.bookings = bookings;
        this.services = services;
        this.packages = packages;
        this.profiles = profiles;
        this.promos = promos;
        this.automation = automation;
        this.users = users;
        this.availability = availability;
        this.settings = settings;
        this.audit = audit;
        this.notifier = notifier;
        this.clock = clock;
        this.lock = lock;
        this.mongo = mongo;
    }

    // ------------------------------------------------------------------ pricing

    public PriceBreakdown quote(QuoteRequest req) {
        if (req.photographerId() == null || req.serviceId() == null || req.packageId() == null) {
            throw ApiException.validation("Choose a service and a package.");
        }
        ServiceOffer svc = services.findById(req.serviceId()).filter(s -> s.getPhotographerId().equals(req.photographerId()))
                .orElseThrow(() -> ApiException.notFound("Package not found"));
        ServicePackage pk = packages.findById(req.packageId()).filter(p -> p.getServiceId().equals(svc.getId())).orElseThrow(() -> ApiException.notFound("Package not found"));
        List<PromoCode> codes = promos.findById(req.photographerId()).map(PromoCodeList::getCodes).orElse(List.of());
        return PricingEngine.price(svc, pk, req.addOnIds() == null ? List.of() : req.addOnIds(), req.promoCode(), req.hours(), codes,
                settings.get().getCommissionPercent(), clock.today());
    }

    // ------------------------------------------------------------------ create

    public Booking create(User customer, CreateBookingRequest req) {
        if (!"client".equals(customer.getRole())) {
            throw ApiException.forbidden("Sign in as a customer to book.");
        }
        if (req.idempotencyKey() == null || !req.idempotencyKey().matches("^[A-Za-z0-9_-]{8,64}$")) {
            throw ApiException.validation("Missing or invalid request id.");
        }
        Booking dup = bookings.findById(req.idempotencyKey()).orElse(null);
        if (dup != null) { // retry of the same submission: same booking, but never somebody else's
            if (!dup.getCustomerId().equals(customer.getUsername())) {
                throw ApiException.conflict("Please try again.");
            }
            return forRole(dup, "client");
        }
        Dates.parseDate(req.date());
        Dates.timeToMinutes(req.startTime());
        String notes = Check.text(req.notes(), "notes", 0, 2000);
        BookingLocation loc = cleanLocation(req.location());
        if (req.photographerId() == null) {
            throw ApiException.validation("Choose a photographer.");
        }
        return lock.with(req.photographerId(), () -> {
            PriceBreakdown pricing = quote(new QuoteRequest(req.photographerId(), req.serviceId(), req.packageId(), req.addOnIds(), req.promoCode(), req.hours()));
            if (req.expectedTotal() == null || Math.abs(pricing.total() - req.expectedTotal()) > 0.005) {
                throw ApiException.conflict("PRICE_CHANGED", "The price changed to " + fmt(pricing.total()) + " MAD. Please review before confirming.");
            }
            ServiceOffer svc = services.findById(req.serviceId()).orElseThrow();
            ServicePackage pk = packages.findById(req.packageId()).orElseThrow();
            PhotographerProfile ph = profiles.findById(req.photographerId()).orElseThrow(() -> ApiException.notFound("Photographer not found"));
            User phUser = users.findByUsername(ph.getId()).orElse(null);
            if (!"approved".equals(ph.getApproval()) || phUser == null || phUser.isSuspended()) {
                throw ApiException.conflict("This photographer is not accepting bookings.");
            }
            if (ph.getId().equals(customer.getUsername())) {
                throw ApiException.forbidden("You cannot book yourself.");
            }
            AvailabilityRules rules = availability.rules(ph.getId());
            int dur = "hourly".equals(svc.getPricingModel()) && req.hours() != null ? (int) Math.round(req.hours() * 60) : pk.getDurationMinutes();
            boolean free = AvailabilityEngine.computeSlots(rules, req.date(), dur, availability.busy(ph.getId(), null), clock.now(), clock.today()).stream()
                    .anyMatch(s -> s.start().equals(req.startTime()) && s.available());
            if (!free) {
                throw ApiException.conflict("SLOT_UNAVAILABLE", "That time slot was just taken. Please choose another.");
            }
            Instant now = clock.instant();
            AutomationSettings.AutoAccept auto = automation.findById(ph.getId()).map(AutomationSettings::getAutoAccept).orElse(null);
            boolean prior = bookings.existsByCustomerIdAndPhotographerIdAndStatus(customer.getUsername(), ph.getId(), "completed");
            double hoursAhead = Duration.between(clock.now(), Dates.combine(req.date(), req.startTime())).toMinutes() / 60.0;
            boolean autoOk = auto != null && auto.enabled() && hoursAhead >= auto.minNoticeHours() && (auto.maxPriceMad() == null || pricing.total() <= auto.maxPriceMad())
                    && (!auto.repeatCustomersOnly() || prior) && !"quote".equals(svc.getPricingModel());
            Booking b = new Booking();
            b.setId(req.idempotencyKey());
            b.setReference("LS-" + (1100 + nextSequence()));
            b.setCustomerId(customer.getUsername());
            b.setCustomerName(customer.getName());
            b.setPhotographerId(ph.getId());
            b.setPhotographerName(ph.getDisplayName());
            b.setPhotographerAvatar(ph.getAvatarUrl());
            b.setServiceId(svc.getId());
            b.setServiceTitle(svc.getTitle());
            b.setCategoryId(svc.getCategoryId());
            b.setPackageId(pk.getId());
            b.setPackageName(pk.getName());
            b.setTier(pk.getTier());
            b.setDate(req.date());
            b.setStartTime(req.startTime());
            b.setEndTime(Dates.minutesToTime(Dates.timeToMinutes(req.startTime()) + dur));
            b.setDurationMinutes(dur);
            b.setLocation(loc);
            List<AddOnLine> lines = new ArrayList<>();
            svc.getAddOns().stream().filter(a -> req.addOnIds() != null && req.addOnIds().contains(a.id())).forEach(a -> lines.add(new AddOnLine(a.id(), a.name(), a.price())));
            b.setAddOns(lines);
            b.setNotes(notes);
            b.setPricing(pricing);
            b.setStatus(autoOk ? "confirmed" : "pending");
            b.setCreatedAt(now);
            b.setUpdatedAt(now);
            List<BookingEvent> hist = new ArrayList<>();
            hist.add(new BookingEvent(now, customer.getName(), "requested", null));
            if (autoOk) {
                hist.add(new BookingEvent(now, "Auto-accept rule", "confirmed", "Matched photographer auto-accept safeguards"));
            }
            b.setHistory(hist);
            bookings.insert(b);
            if (pricing.promoCode() != null) {
                countPromoUse(ph.getId(), pricing.promoCode());
            }
            notifier.notify(ph.getId(), NotificationType.BOOKING, autoOk ? "Booking auto-confirmed" : "New booking request",
                    customer.getName() + " · " + svc.getTitle() + " · " + req.date(), "/studio/bookings", "bookings:new");
            notifier.notify(customer.getUsername(), NotificationType.BOOKING, autoOk ? "Booking confirmed" : "Request sent",
                    autoOk ? ph.getDisplayName() + " confirmed " + b.getReference() + "." : "Waiting for " + ph.getDisplayName() + " to confirm " + b.getReference() + ".",
                    "/account/bookings", "booking:" + b.getId());
            return forRole(b, "client");
        });
    }

    private long nextSequence() {
        Counter c = mongo.findAndModify(Query.query(Criteria.where("_id").is("booking-reference")), new Update().inc("value", 1),
                FindAndModifyOptions.options().returnNew(true).upsert(true), Counter.class);
        return c == null ? System.currentTimeMillis() % 100000 : c.getValue();
    }

    private void countPromoUse(String photographerId, String code) {
        promos.findById(photographerId).ifPresent(l -> {
            List<PromoCode> next = new ArrayList<>();
            for (PromoCode c : l.getCodes()) {
                next.add(c.code().equalsIgnoreCase(code) ? new PromoCode(c.id(), c.code(), c.percent(), c.active(), c.expiresAt(), c.uses() + 1, c.maxUses()) : c);
            }
            l.setCodes(next);
            promos.save(l);
        });
    }

    private static BookingLocation cleanLocation(BookingLocation l) {
        if (l == null) {
            throw ApiException.validation("Where should the session take place?", "location", "Required");
        }
        if (l.point() != null && (Math.abs(l.point().lat()) > 90 || Math.abs(l.point().lng()) > 180)) {
            throw ApiException.validation("Invalid map position.", "location", "Invalid");
        }
        return new BookingLocation(Check.text(l.address(), "address", 0, 200), Check.text(l.city(), "city", 1, 60), l.point());
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ read

    private String roleOf(User u) {
        return u.getRole();
    }

    private boolean participant(Booking b, User me) {
        return "admin".equals(me.getRole()) || b.getCustomerId().equals(me.getUsername()) || b.getPhotographerId().equals(me.getUsername());
    }

    private Booking visible(String id, User me) {
        Booking b = bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!participant(b, me)) {
            throw ApiException.notFound("Booking not found"); // do not reveal that it exists
        }
        return b;
    }

    /** Customers never see the platform's commission. */
    private Booking forRole(Booking b, String role) {
        if ("client".equals(role) && b.getPricing() != null && b.getPricing().platformFee() != 0) {
            PriceBreakdown p = b.getPricing();
            b.setPricing(new PriceBreakdown(p.pricingModel(), p.packagePrice(), p.addOnsTotal(), p.discount(), p.promoCode(), p.subtotal(), 0, p.total(), p.currency(), p.quoteId()));
        }
        return b;
    }

    public Booking get(String id, User me) {
        return forRole(visible(id, me), roleOf(me));
    }

    public PageResult<Booking> list(User me, BookingQuery q) {
        List<Booking> list = switch (me.getRole()) {
            case "client" -> bookings.findByCustomerId(me.getUsername());
            case "photographer" -> bookings.findByPhotographerId(me.getUsername());
            default -> bookings.findAll();
        };
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(q.pageSize(), 1000));
        boolean admin = "admin".equals(me.getRole());
        String needle = q.q() == null ? null : q.q().trim().toLowerCase();
        List<Booking> f = list.stream().filter(b -> q.status() == null || q.status().isEmpty() || "all".equals(q.status()) || b.getStatus().equals(q.status()))
                .filter(b -> !admin || q.photographerId() == null || b.getPhotographerId().equals(q.photographerId()))
                .filter(b -> !admin || q.customerId() == null || b.getCustomerId().equals(q.customerId()))
                .filter(b -> q.from() == null || b.getDate().compareTo(q.from()) >= 0)
                .filter(b -> q.to() == null || b.getDate().compareTo(q.to()) <= 0)
                .filter(b -> needle == null || needle.isEmpty() || (b.getReference() + " " + b.getCustomerName() + " " + b.getPhotographerName() + " " + b.getServiceTitle()).toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Booking::getDate).thenComparing(Booking::getStartTime).reversed()).toList();
        int from = Math.min((page - 1) * size, f.size());
        List<Booking> items = new ArrayList<>(f.subList(from, Math.min(from + size, f.size())));
        items.forEach(b -> forRole(b, me.getRole()));
        return new PageResult<>(items, f.size(), page, size);
    }

    // ------------------------------------------------------------------ transitions

    private void log(Booking b, User me, String type, String note) {
        Instant now = clock.instant();
        b.setUpdatedAt(now);
        List<BookingEvent> h = new ArrayList<>(b.getHistory());
        h.add(new BookingEvent(now, me.getName(), type, note));
        b.setHistory(h);
    }

    private boolean conflicts(Booking b, String date, String start) {
        AvailabilityRules rules = availability.rules(b.getPhotographerId());
        return AvailabilityEngine.computeSlots(rules, date, b.getDurationMinutes(), availability.busy(b.getPhotographerId(), b.getId()), AvailabilityEngine.EPOCH, clock.today())
                .stream().noneMatch(s -> s.start().equals(start) && s.available());
    }

    private void requirePhotographerSide(Booking b, User me) {
        if (!"admin".equals(me.getRole()) && !("photographer".equals(me.getRole()) && b.getPhotographerId().equals(me.getUsername()))) {
            throw ApiException.forbidden("Only the photographer can do that.");
        }
    }

    public Booking setStatus(String id, String status, String note, User me) {
        Check.oneOf(status, "status", "confirmed", "rejected", "completed", "expired");
        String cleanNote = note == null ? null : Check.text(note, "note", 0, 500);
        Booking found = visible(id, me);
        requirePhotographerSide(found, me);
        return lock.with(found.getPhotographerId(), () -> {
            Booking b = visible(id, me);
            boolean admin = "admin".equals(me.getRole());
            Map<String, List<String>> allowed = Map.of("pending", admin ? List.of("confirmed", "rejected", "expired") : List.of("confirmed", "rejected"), "confirmed", List.of("completed"));
            if (!allowed.getOrDefault(b.getStatus(), List.of()).contains(status)) {
                throw ApiException.conflict("Cannot move a " + b.getStatus() + " booking to " + status + ".");
            }
            if ("confirmed".equals(status) && conflicts(b, b.getDate(), b.getStartTime())) {
                throw ApiException.conflict("SLOT_UNAVAILABLE", "This slot conflicts with another booking or blocked time.");
            }
            if ("completed".equals(status) && b.getDate().compareTo(clock.today().toString()) > 0) {
                throw ApiException.conflict("A session can only be completed on or after its date.");
            }
            b.setStatus(status);
            log(b, me, status, cleanNote);
            bookings.save(b);
            if ("completed".equals(status)) {
                profiles.incrementCompleted(b.getPhotographerId());
            }
            notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Booking " + status, b.getPhotographerName() + " marked " + b.getReference() + " as " + status + ".",
                    "/account/bookings", "booking:" + b.getId());
            if ("completed".equals(status)) {
                notifier.notify(b.getCustomerId(), NotificationType.REVIEW, "How was your session?", "Leave a review for your completed booking.", "/account/bookings", "review:" + b.getId());
            }
            return forRole(b, me.getRole());
        });
    }

    public Booking cancel(String id, String reason, User me) {
        String why = Check.text(reason, "reason", 0, 500);
        Booking found = visible(id, me);
        return lock.with(found.getPhotographerId(), () -> {
            Booking b = visible(id, me);
            if (!LIVE.contains(b.getStatus())) {
                throw ApiException.conflict("Only pending or confirmed bookings can be cancelled.");
            }
            PhotographerProfile ph = profiles.findById(b.getPhotographerId()).orElseThrow();
            double hours = Duration.between(clock.now(), Dates.combine(b.getDate(), b.getStartTime())).toMinutes() / 60.0;
            int refund = PricingEngine.refundPercent(me.getRole(), b.getStatus(), hours, ph.getCancellationPolicy());
            String by = "client".equals(me.getRole()) ? "client" : "admin".equals(me.getRole()) ? "admin" : "photographer";
            b.setStatus("cancelled");
            b.setCancellation(new Cancellation(by, why, clock.instant(), refund));
            log(b, me, "cancelled", why);
            bookings.save(b);
            boolean byClient = "client".equals(me.getRole());
            notifier.notify(byClient ? b.getPhotographerId() : b.getCustomerId(), NotificationType.BOOKING, "Booking cancelled", b.getReference() + " was cancelled.",
                    byClient ? "/studio/bookings" : "/account/bookings", "booking:" + b.getId());
            return forRole(b, me.getRole());
        });
    }

    public Booking requestReschedule(String id, String date, String startTime, User me) {
        Dates.parseDate(date);
        Dates.timeToMinutes(startTime);
        if ("admin".equals(me.getRole())) {
            throw ApiException.forbidden("Only the customer or the photographer can reschedule.");
        }
        Booking found = visible(id, me);
        return lock.with(found.getPhotographerId(), () -> {
            Booking b = visible(id, me);
            if (!LIVE.contains(b.getStatus())) {
                throw ApiException.conflict("This booking can no longer be rescheduled.");
            }
            if (conflicts(b, date, startTime)) {
                throw ApiException.conflict("SLOT_UNAVAILABLE", "That slot is not available.");
            }
            boolean byClient = "client".equals(me.getRole());
            b.setRescheduleRequest(new RescheduleRequest(date, startTime, byClient ? "client" : "photographer", clock.instant()));
            log(b, me, "reschedule_requested", date + " " + startTime);
            bookings.save(b);
            notifier.notify(byClient ? b.getPhotographerId() : b.getCustomerId(), NotificationType.BOOKING, "Reschedule requested", b.getReference() + ": " + date + " at " + startTime,
                    byClient ? "/studio/bookings" : "/account/bookings", "booking:" + b.getId());
            return forRole(b, me.getRole());
        });
    }

    /** Only the side that did NOT ask can answer. */
    public Booking respondReschedule(String id, boolean accept, User me) {
        Booking found = visible(id, me);
        return lock.with(found.getPhotographerId(), () -> {
            Booking b = visible(id, me);
            RescheduleRequest r = b.getRescheduleRequest();
            if (r == null) {
                throw ApiException.notFound("No reschedule request");
            }
            String side = "client".equals(me.getRole()) ? "client" : "photographer".equals(me.getRole()) ? "photographer" : "admin";
            if (side.equals(r.requestedBy()) || "admin".equals(side)) {
                throw ApiException.forbidden("The other side has to answer a reschedule request.");
            }
            if (accept) {
                if (!LIVE.contains(b.getStatus())) {
                    throw ApiException.conflict("This booking can no longer be rescheduled.");
                }
                if (conflicts(b, r.date(), r.startTime())) {
                    throw ApiException.conflict("SLOT_UNAVAILABLE", "The requested slot is no longer free.");
                }
                b.setDate(r.date());
                b.setStartTime(r.startTime());
                b.setEndTime(Dates.minutesToTime(Dates.timeToMinutes(r.startTime()) + b.getDurationMinutes()));
            }
            b.setRescheduleRequest(null);
            log(b, me, accept ? "reschedule_accepted" : "reschedule_declined", null);
            bookings.save(b);
            notifier.notify("client".equals(side) ? b.getPhotographerId() : b.getCustomerId(), NotificationType.BOOKING, accept ? "Reschedule accepted" : "Reschedule declined",
                    b.getReference(), "client".equals(side) ? "/studio/bookings" : "/account/bookings", "booking:" + b.getId());
            return forRole(b, me.getRole());
        });
    }

    public Booking setDelivery(String id, String status, User me) {
        Check.oneOf(status, "status", "not_started", "editing", "delivered");
        Booking b = visible(id, me);
        requirePhotographerSide(b, me);
        b.setDeliveryStatus(status);
        log(b, me, "delivery_" + status, null);
        bookings.save(b);
        if ("delivered".equals(status)) {
            notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Photos delivered", b.getReference() + " has been marked as delivered.", "/account/bookings", "booking:" + b.getId());
        }
        return forRole(b, me.getRole());
    }

    public Booking openDispute(String id, String reason, User me) {
        String why = Check.text(reason, "reason", 5, 1000);
        Booking b = visible(id, me);
        if (b.getDispute() != null && "open".equals(b.getDispute().state())) {
            throw ApiException.conflict("A dispute is already open.");
        }
        b.setDispute(new Dispute("open", why, clock.instant(), null));
        log(b, me, "dispute_opened", why);
        bookings.save(b);
        return forRole(b, me.getRole());
    }

    public Booking resolveDispute(String id, String resolution, User me) {
        if (!"admin".equals(me.getRole())) {
            throw ApiException.forbidden("Admins only");
        }
        String res = Check.text(resolution, "resolution", 3, 1000);
        Booking b = visible(id, me);
        if (b.getDispute() == null) {
            throw ApiException.notFound("No dispute");
        }
        Dispute d = b.getDispute();
        b.setDispute(new Dispute("resolved", d.reason(), d.openedAt(), res));
        log(b, me, "dispute_resolved", res);
        bookings.save(b);
        audit.log(me.getName(), "resolved dispute", b.getReference(), res);
        return b;
    }

    /** Records a refund. Nothing is ever charged by this platform yet, so only bookings already marked 'paid' can be refunded. */
    public Booking markRefund(String id, String kind, User me) {
        if (!"admin".equals(me.getRole())) {
            throw ApiException.forbidden("Admins only");
        }
        Check.oneOf(kind, "kind", "refunded", "partially_refunded");
        Booking b = visible(id, me);
        if (!"paid".equals(b.getPaymentStatus())) {
            throw ApiException.conflict("Only paid bookings can be refunded.");
        }
        b.setPaymentStatus(kind);
        log(b, me, kind, null);
        bookings.save(b);
        audit.log(me.getName(), "recorded " + kind, b.getReference(), null);
        return b;
    }

    /** Used by the review service. */
    public Booking raw(String id) {
        return bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking not found"));
    }
}
