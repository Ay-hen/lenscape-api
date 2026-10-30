package photography.photography.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import photography.photography.api.KeyedLock;
import photography.photography.api.PlatformClock;
import photography.photography.model.Booking;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Values.BookingEvent;
import photography.photography.model.Values.MessageTemplate;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;

class AutomationRunnerTest {
    BookingRepo bookings = mock(BookingRepo.class);
    AutomationRepo automation = mock(AutomationRepo.class);
    Notifier notifier = mock(Notifier.class);
    PlatformClock clock = new PlatformClock("Africa/Casablanca");
    AutomationRunner runner;
    AutomationSettings settings = new AutomationSettings();
    final List<Booking> store = new ArrayList<>();
    final Instant now = Instant.parse("2026-11-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        runner = new AutomationRunner(bookings, automation, notifier, clock, new KeyedLock());
        settings.setPhotographerId("bob");
        settings.setTemplates(List.of(new MessageTemplate("t2", "Reminder", "See you {{date}} at {{time}}, {{customer}}!", "message")));
        when(automation.findById("bob")).thenReturn(Optional.of(settings));
        when(bookings.findByStatusIn(any())).thenAnswer(i -> store);
        when(bookings.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
    }

    Booking booking(String status, Instant created, String date) {
        Booking b = new Booking();
        b.setId("b" + store.size());
        b.setReference("LS-1");
        b.setCustomerId("alice");
        b.setCustomerName("Alice");
        b.setPhotographerId("bob");
        b.setPhotographerName("Bob");
        b.setServiceTitle("Portrait");
        b.setStatus(status);
        b.setCreatedAt(created);
        b.setDate(date);
        b.setStartTime("10:00");
        b.setHistory(new ArrayList<>());
        store.add(b);
        return b;
    }

    @Test
    void unansweredRequestsAreNudgedOnceThenExpire() {
        Booking b = booking("pending", now.minus(Duration.ofHours(30)), "2026-12-01");
        assertEquals(1, runner.runOnce(now));
        verify(notifier, times(1)).notify(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        assertEquals(0, runner.runOnce(now), "the nudge is sent only once");
        assertEquals("pending", b.getStatus());
        assertEquals(1, runner.runOnce(now.plus(Duration.ofHours(48))));
        assertEquals("expired", b.getStatus());
    }

    @Test
    void disabledRulesDoNothing() {
        settings.setAutoExpire(new AutomationSettings.AutoExpire(false, 72));
        settings.setPendingNudge(new AutomationSettings.PendingNudge(false, 24));
        Booking b = booking("pending", now.minus(Duration.ofDays(30)), "2026-12-01");
        assertEquals(0, runner.runOnce(now));
        assertEquals("pending", b.getStatus());
    }

    @Test
    void remindersFireInsideTheirWindowOnlyOnceAndUseTheTemplate() {
        // session 2026-11-11 10:00 Casablanca (UTC+1 in November) = 09:00Z; 24 h reminder is due from 2026-11-10T09:00Z
        Booking b = booking("confirmed", now.minus(Duration.ofDays(5)), "2026-11-11");
        assertEquals(1, runner.runOnce(now), "inside the 24 h window");
        assertTrue(b.getHistory().stream().anyMatch(e -> e.type().equals("reminder_24")));
        assertEquals(0, runner.runOnce(now.plus(Duration.ofMinutes(5))));
        verify(notifier).notify(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.eq("See you 2026-11-11 at 10:00, Alice!"), anyString(), anyString());
    }

    @Test
    void noReminderForPastOrFarFutureSessions() {
        booking("confirmed", now.minus(Duration.ofDays(5)), "2026-11-09");
        booking("confirmed", now.minus(Duration.ofDays(5)), "2026-12-24");
        assertEquals(0, runner.runOnce(now));
    }

    @Test
    void thankYouAndReviewRequestFollowCompletionWithTheirDelays() {
        Booking b = booking("completed", now.minus(Duration.ofDays(9)), "2026-11-01");
        b.getHistory().add(new BookingEvent(now.minus(Duration.ofHours(10)), "Bob", "completed", null));
        assertEquals(0, runner.runOnce(now), "delays (follow-up off by default, review after 72 h) have not elapsed");
        assertEquals(1, runner.runOnce(now.plus(Duration.ofHours(70))), "review request after 72 h");
        assertEquals(0, runner.runOnce(now.plus(Duration.ofHours(80))), "only once");
        b.setReviewed(true);
        b.getHistory().removeIf(e -> e.type().equals("review_requested"));
        assertEquals(0, runner.runOnce(now.plus(Duration.ofHours(90))), "no request once reviewed");
    }
}
