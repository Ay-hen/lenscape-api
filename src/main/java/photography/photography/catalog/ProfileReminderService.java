package photography.photography.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.model.PhotographerProfile;
import photography.photography.notification.Notification;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;

/**
 * Profile-completion reminders. Cadence (all configurable, see application.properties): first reminder {@code first-delay} after sign-up, then one every
 * {@code interval}, at most {@code max-reminders}; never a second one while the previous is unread; "remind me later" snoozes; "dismiss" is only
 * accepted from {@code dismiss-threshold} % upwards; nothing is sent at 100 %. State is persisted, so restarts neither repeat nor lose anything.
 * Single-instance safe (like the rest of the scheduler code).
 */
@Service
public class ProfileReminderService {
    private static final Logger log = LoggerFactory.getLogger(ProfileReminderService.class);
    static final String GROUP = "profile-completion";

    public record CompletionView(int percent, List<ProfileCompletion.Item> items, int dismissThreshold, boolean canDismiss,
                                 boolean dismissed, Instant snoozedUntil, boolean showReminder) {
    }

    private final PhotographerProfileRepo profiles;
    private final ServiceOfferRepo services;
    private final ServicePackageRepo packages;
    private final ProfileReminderRepo states;
    private final Notifier notifier;
    private final MongoTemplate mongo;
    private final boolean enabled;
    private final Duration firstDelay;
    private final Duration interval;
    private final Duration snooze;
    private final int maxReminders;
    private final int dismissThreshold;
    private final int minPhotos;

    public ProfileReminderService(PhotographerProfileRepo profiles, ServiceOfferRepo services, ServicePackageRepo packages, ProfileReminderRepo states,
                                  Notifier notifier, MongoTemplate mongo,
                                  @Value("${app.profile-reminder.enabled:true}") boolean enabled,
                                  @Value("${app.profile-reminder.first-delay:P1D}") Duration firstDelay,
                                  @Value("${app.profile-reminder.interval:P7D}") Duration interval,
                                  @Value("${app.profile-reminder.snooze:P3D}") Duration snooze,
                                  @Value("${app.profile-reminder.max-reminders:6}") int maxReminders,
                                  @Value("${app.profile-reminder.dismiss-threshold:80}") int dismissThreshold,
                                  @Value("${app.profile.min-portfolio-photos:3}") int minPhotos) {
        this.profiles = profiles;
        this.services = services;
        this.packages = packages;
        this.states = states;
        this.notifier = notifier;
        this.mongo = mongo;
        this.enabled = enabled;
        this.firstDelay = firstDelay;
        this.interval = interval;
        this.snooze = snooze;
        this.maxReminders = maxReminders;
        this.dismissThreshold = dismissThreshold;
        this.minPhotos = minPhotos;
    }

    // ------------------------------------------------------------------ API used by the photographer's dashboard

    public CompletionView status(String id) {
        return view(completion(profile(id)), state(id), Instant.now());
    }

    /** "Remind me later": always allowed. */
    public CompletionView snooze(String id) {
        ProfileCompletion.Result r = completion(profile(id));
        ProfileReminderState s = state(id);
        Instant now = Instant.now();
        s.setSnoozedUntil(now.plus(snooze));
        states.save(s);
        return view(r, s, now);
    }

    /** "Dismiss": only from the threshold (80 %) upwards. Enforced here, not only in the UI. */
    public CompletionView dismiss(String id) {
        ProfileCompletion.Result r = completion(profile(id));
        if (r.percent() < dismissThreshold) {
            throw ApiException.conflict("You can dismiss this reminder once your profile is " + dismissThreshold + "% complete.");
        }
        ProfileReminderState s = state(id);
        Instant now = Instant.now();
        s.setDismissedAt(now);
        states.save(s);
        return view(r, s, now);
    }

    // ------------------------------------------------------------------ scheduler

    @Scheduled(fixedDelayString = "${app.profile-reminder.check-interval-ms:3600000}", initialDelayString = "${app.profile-reminder.initial-delay-ms:120000}")
    public void scheduled() {
        try {
            int n = runOnce(Instant.now());
            if (n > 0) {
                log.info("Profile reminders: {} sent", n);
            }
        } catch (RuntimeException e) {
            log.error("Profile reminder run failed", e);
        }
    }

    /** @return number of reminders sent */
    public int runOnce(Instant now) {
        if (!enabled) {
            return 0;
        }
        int sent = 0;
        for (PhotographerProfile p : profiles.findAll()) {
            try {
                if (remind(p, now)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Profile reminder skipped for {}: {}", p.getId(), e.toString());
            }
        }
        return sent;
    }

    private boolean remind(PhotographerProfile p, Instant now) {
        ProfileCompletion.Result r = completion(p);
        ProfileReminderState s = state(p.getId());
        if (r.percent() >= 100) { // finished: stop for good and close a reminder that is still unread
            if (s.getClearedAt() == null && s.getSent() > 0) {
                mongo.updateMulti(openReminders(p.getId()), Update.update("read", true), Notification.class);
                s.setClearedAt(now);
                states.save(s);
            }
            return false;
        }
        boolean dirty = false;
        if (s.getClearedAt() != null) {
            s.setClearedAt(null);
            dirty = true;
        }
        if (s.getDismissedAt() != null) {
            if (r.percent() >= dismissThreshold) {
                if (dirty) {
                    states.save(s);
                }
                return false;
            }
            s.setDismissedAt(null); // fell below the threshold again: the dismissal no longer applies
            dirty = true;
        }
        if ((s.getSnoozedUntil() != null && now.isBefore(s.getSnoozedUntil())) || s.getSent() >= maxReminders) {
            if (dirty) {
                states.save(s);
            }
            return false;
        }
        Instant due = s.getLastRemindedAt() != null ? s.getLastRemindedAt().plus(interval)
                : (p.getJoinedAt() != null ? p.getJoinedAt() : Instant.EPOCH).plus(firstDelay);
        if (now.isBefore(due)) {
            if (dirty) {
                states.save(s);
            }
            return false;
        }
        boolean stillUnread = mongo.exists(openReminders(p.getId()), Notification.class);
        s.setLastRemindedAt(now);
        if (!stillUnread) {
            s.setSent(s.getSent() + 1);
        }
        states.save(s); // saved BEFORE notifying: a crash can lose a reminder but never duplicate one
        if (stillUnread) {
            return false; // the previous reminder is still unread: never stack another, wait one more interval
        }
        ProfileCompletion.Item next = r.items().stream().filter(i -> !i.done()).max(Comparator.comparingInt(ProfileCompletion.Item::weight)).orElse(null);
        notifier.notify(p.getId(), NotificationType.SYSTEM, "Finish setting up your profile",
                "Your profile is " + r.percent() + "% complete." + (next == null ? "" : " Next: " + next.label() + "."),
                next == null ? "/studio/profile" : next.link(), GROUP);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static Query openReminders(String id) {
        return Query.query(Criteria.where("userId").is(id).and("groupKey").is(GROUP).and("read").is(false));
    }

    private PhotographerProfile profile(String id) {
        return profiles.findById(id).orElseThrow(() -> ApiException.notFound("Profile not found"));
    }

    private ProfileReminderState state(String id) {
        return states.findById(id).orElseGet(() -> {
            ProfileReminderState n = new ProfileReminderState();
            n.setPhotographerId(id);
            return n;
        });
    }

    private ProfileCompletion.Result completion(PhotographerProfile p) {
        boolean svc = services.findByPhotographerId(p.getId()).stream().anyMatch(s -> "published".equals(s.getStatus()));
        boolean pkg = packages.findByPhotographerId(p.getId()).stream().anyMatch(k -> "published".equals(k.getStatus()));
        return ProfileCompletion.of(p, svc, pkg, minPhotos);
    }

    private CompletionView view(ProfileCompletion.Result r, ProfileReminderState s, Instant now) {
        boolean dismissed = s.getDismissedAt() != null && r.percent() >= dismissThreshold;
        boolean snoozed = s.getSnoozedUntil() != null && now.isBefore(s.getSnoozedUntil());
        return new CompletionView(r.percent(), r.items(), dismissThreshold, r.percent() >= dismissThreshold && r.percent() < 100, dismissed,
                snoozed ? s.getSnoozedUntil() : null, r.percent() < 100 && !dismissed && !snoozed);
    }
}