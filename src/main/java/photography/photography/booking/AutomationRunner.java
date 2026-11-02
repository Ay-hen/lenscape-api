package photography.photography.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import photography.photography.api.KeyedLock;
import photography.photography.api.PlatformClock;
import photography.photography.domain.Dates;
import photography.photography.model.Booking;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Values.BookingEvent;
import photography.photography.model.Values.MessageTemplate;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;

/**
 * Executes the photographer's automation rules that deliver as in-app notifications: auto-expiry and nudges for unanswered requests, session reminders,
 * thank-you follow-ups and review requests. Every action is recorded as a booking-history event, so a rule fires at most once per booking even if the
 * server restarts or the job overlaps. NOT executed (stored preferences only): e-mail delivery, automatic confirmation chat messages,
 * repeat-customer offers, gallery tracking. Auto-accept is applied at booking time (BookingService.create).
 */
@Component
public class AutomationRunner {
    private static final Logger log = LoggerFactory.getLogger(AutomationRunner.class);

    private final BookingRepo bookings;
    private final AutomationRepo automation;
    private final Notifier notifier;
    private final PlatformClock clock;
    private final KeyedLock lock;
    /** When the platform's own 24 h reminder is on, the photographer's "24" entry is skipped so nobody is reminded twice. */
    @org.springframework.beans.factory.annotation.Value("${app.booking-reminder.enabled:false}")
    private boolean platformReminder;

    public AutomationRunner(BookingRepo bookings, AutomationRepo automation, Notifier notifier, PlatformClock clock, KeyedLock lock) {
        this.bookings = bookings;
        this.automation = automation;
        this.notifier = notifier;
        this.clock = clock;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${app.automation.interval-ms:300000}", initialDelayString = "${app.automation.initial-delay-ms:60000}")
    public void scheduled() {
        try {
            int n = runOnce(clock.instant());
            if (n > 0) {
                log.info("Automation: {} action(s) executed", n);
            }
        } catch (RuntimeException e) {
            log.error("Automation run failed", e);
        }
    }

    /** @return number of actions taken */
    public int runOnce(Instant now) {
        Map<String, AutomationSettings> settings = new HashMap<>();
        int actions = 0;
        for (Booking b : bookings.findByStatusIn(List.of("pending", "confirmed", "completed"))) {
            AutomationSettings a = settings.computeIfAbsent(b.getPhotographerId(), id -> automation.findById(id).orElse(null));
            if (a == null) {
                continue;
            }
            final int[] n = {0};
            lock.with(b.getPhotographerId(), () -> {
                n[0] = apply(b, a, now);
                return null;
            });
            actions += n[0];
        }
        return actions;
    }

    private int apply(Booking b, AutomationSettings a, Instant now) {
        int done = 0;
        if ("pending".equals(b.getStatus()) && b.getCreatedAt() != null) {
            Duration age = Duration.between(b.getCreatedAt(), now);
            if (a.getAutoExpire().enabled() && age.toHours() >= a.getAutoExpire().afterHours()) {
                b.setStatus("expired");
                event(b, now, "expired", "No answer within " + a.getAutoExpire().afterHours() + " h");
                bookings.save(b);
                notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Request expired", b.getPhotographerName() + " did not answer " + b.getReference() + " in time.", "/account/bookings", "booking:" + b.getId());
                notifier.notify(b.getPhotographerId(), NotificationType.BOOKING, "Request expired", b.getReference() + " expired without an answer.", "/studio/bookings", "booking:" + b.getId());
                return 1;
            }
            if (a.getPendingNudge().enabled() && age.toHours() >= a.getPendingNudge().afterHours() && !has(b, "nudged")) {
                event(b, now, "nudged", null);
                bookings.save(b);
                notifier.notify(b.getPhotographerId(), NotificationType.BOOKING, "Reply to a booking request", b.getCustomerName() + " is waiting for an answer on " + b.getReference() + ".", "/studio/bookings", "nudge:" + b.getId());
                done++;
            }
        }
        if ("confirmed".equals(b.getStatus()) && a.getReminders().enabled()) {
            Instant start = Dates.combine(b.getDate(), b.getStartTime()).atZone(clock.zone()).toInstant();
            if (now.isBefore(start)) {
                List<Integer> due = new ArrayList<>();
                for (Integer h : a.getReminders().hoursBefore()) {
                    if (platformReminder && Integer.valueOf(24).equals(h)) {
                        continue;
                    }
                    if (!now.isBefore(start.minus(Duration.ofHours(h))) && !has(b, "reminder_" + h)) {
                        due.add(h);
                    }
                }
                if (!due.isEmpty()) {
                    due.forEach(h -> event(b, now, "reminder_" + h, null));
                    bookings.save(b);
                    notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Session reminder", render(a, a.getReminders().templateId(), b,
                            "Reminder: your " + b.getServiceTitle() + " session is on " + b.getDate() + " at " + b.getStartTime() + "."), "/account/bookings", "reminder:" + b.getId());
                    done++;
                }
            }
        }
        if ("completed".equals(b.getStatus())) {
            Instant completed = completedAt(b);
            if (completed != null) {
                if (a.getFollowUp().enabled() && !has(b, "followup_sent") && !now.isBefore(completed.plus(Duration.ofHours(a.getFollowUp().delayHours())))) {
                    event(b, now, "followup_sent", null);
                    bookings.save(b);
                    notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Thank you!", render(a, a.getFollowUp().templateId(), b,
                            "Thank you for booking " + b.getPhotographerName() + "."), "/account/bookings", "followup:" + b.getId());
                    done++;
                }
                if (a.getReviewRequest().enabled() && !b.isReviewed() && !has(b, "review_requested") && !now.isBefore(completed.plus(Duration.ofHours(a.getReviewRequest().delayHours())))) {
                    event(b, now, "review_requested", null);
                    bookings.save(b);
                    notifier.notify(b.getCustomerId(), NotificationType.REVIEW, "How was your session?", "Tell others about " + b.getPhotographerName() + " — it takes a minute.", "/account/bookings", "review:" + b.getId());
                    done++;
                }
            }
        }
        return done;
    }

    private static boolean has(Booking b, String type) {
        return b.getHistory().stream().anyMatch(e -> type.equals(e.type()));
    }

    private static Instant completedAt(Booking b) {
        Instant at = null;
        for (BookingEvent e : b.getHistory()) {
            if ("completed".equals(e.type())) {
                at = e.at();
            }
        }
        return at;
    }

    private static void event(Booking b, Instant now, String type, String note) {
        List<BookingEvent> h = new ArrayList<>(b.getHistory());
        h.add(new BookingEvent(now, "Automation", type, note));
        b.setHistory(h);
        b.setUpdatedAt(now);
    }

    /** Fills {{customer}}, {{service}}, {{date}}, {{time}}, {{reference}}, {{city}} in the photographer's template; falls back to the default text. */
    static String render(AutomationSettings a, String templateId, Booking b, String fallback) {
        for (MessageTemplate t : a.getTemplates()) {
            if (t.id().equals(templateId)) {
                return t.body().replace("{{customer}}", nz(b.getCustomerName())).replace("{{service}}", nz(b.getServiceTitle())).replace("{{date}}", b.getDate())
                        .replace("{{time}}", b.getStartTime()).replace("{{reference}}", nz(b.getReference())).replace("{{city}}", b.getLocation() == null ? "" : nz(b.getLocation().city()));
            }
        }
        return fallback;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}