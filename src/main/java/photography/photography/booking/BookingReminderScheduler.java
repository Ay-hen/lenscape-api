package photography.photography.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import photography.photography.api.PlatformClock;
import photography.photography.domain.Dates;
import photography.photography.model.Booking;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;

/**
 * "24 hours to go" notification to the customer AND the photographer, for CONFIRMED bookings only, exactly once.
 * Fires inside [start - lead, start - lead + grace]; bookings confirmed after that moment, or missed during a long outage, are skipped rather than
 * reminded late. Duplicate protection is a unique row in {@code sent_reminders}, so restarts and overlapping runs are harmless.
 */
@Component
public class BookingReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(BookingReminderScheduler.class);

    private final BookingRepo bookings;
    private final SentReminderRepo sent;
    private final Notifier notifier;
    private final PlatformClock clock;
    private final boolean enabled;
    private final Duration lead;
    private final Duration grace;

    public BookingReminderScheduler(BookingRepo bookings, SentReminderRepo sent, Notifier notifier, PlatformClock clock,
                                    @Value("${app.booking-reminder.enabled:true}") boolean enabled,
                                    @Value("${app.booking-reminder.lead:PT24H}") Duration lead,
                                    @Value("${app.booking-reminder.grace:PT2H}") Duration grace) {
        this.bookings = bookings;
        this.sent = sent;
        this.notifier = notifier;
        this.clock = clock;
        this.enabled = enabled;
        this.lead = lead;
        this.grace = grace;
    }

    @Scheduled(fixedDelayString = "${app.booking-reminder.interval-ms:60000}", initialDelayString = "${app.booking-reminder.initial-delay-ms:30000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            int n = runOnce(clock.instant());
            if (n > 0) {
                log.info("Booking reminders: {} booking(s)", n);
            }
        } catch (RuntimeException e) {
            log.error("Booking reminder run failed", e);
        }
    }

    /** @return number of bookings reminded */
    public int runOnce(Instant now) {
        LocalDate today = clock.today();
        int count = 0;
        for (Booking candidate : bookings.findByStatusAndDateRange("confirmed", today.toString(), today.plusDays(2).toString())) {
            try {
                if (remind(candidate, now)) {
                    count++;
                }
            } catch (RuntimeException e) {
                log.warn("Reminder skipped for booking {}: {}", candidate.getId(), e.toString());
            }
        }
        return count;
    }

    private boolean remind(Booking candidate, Instant now) {
        Instant start = Dates.combine(candidate.getDate(), candidate.getStartTime()).atZone(clock.zone()).toInstant();
        Instant dueAt = start.minus(lead);
        if (now.isBefore(dueAt) || now.isAfter(dueAt.plus(grace)) || !now.isBefore(start)) {
            return false;
        }
        Booking b = bookings.findById(candidate.getId()).orElse(null); // fresh read: it may have been cancelled or moved a moment ago
        if (b == null || !"confirmed".equals(b.getStatus()) || !b.getDate().equals(candidate.getDate()) || !b.getStartTime().equals(candidate.getStartTime())) {
            return false;
        }
        String key = b.getId() + ":" + lead.toHours() + "h:" + b.getDate() + "T" + b.getStartTime();
        try {
            sent.insert(new SentReminder(key, b.getId(), now)); // claim first, notify second: a crash can lose a reminder, never duplicate it
        } catch (DuplicateKeyException alreadySent) {
            return false;
        }
        String when = b.getDate() + " at " + b.getStartTime();
        notifier.notify(b.getCustomerId(), NotificationType.BOOKING, "Session in 24 hours",
                "Reminder: your " + b.getServiceTitle() + " with " + b.getPhotographerName() + " is on " + when + ".", "/account/bookings", "reminder24:" + b.getId());
        notifier.notify(b.getPhotographerId(), NotificationType.BOOKING, "Session in 24 hours",
                "Reminder: " + b.getServiceTitle() + " with " + b.getCustomerName() + " is on " + when + ".", "/studio/bookings", "reminder24:" + b.getId());
        return true;
    }
}