package photography.photography.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.domain.AvailabilityEngine;
import photography.photography.domain.Dates;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Booking;
import photography.photography.model.Values.BusyInterval;
import photography.photography.model.Values.Slot;
import photography.photography.respository.AvailabilityRepo;
import photography.photography.respository.BookingRepo;

@Service
public class AvailabilityService {
    private final AvailabilityRepo repo;
    private final BookingRepo bookings;
    private final PlatformClock clock;

    public AvailabilityService(AvailabilityRepo repo, BookingRepo bookings, PlatformClock clock) {
        this.repo = repo;
        this.bookings = bookings;
        this.clock = clock;
    }

    public AvailabilityRules rules(String photographerId) {
        return repo.findById(photographerId).orElseThrow(() -> ApiException.notFound("No availability"));
    }

    public AvailabilityRules save(String me, AvailabilityRules in) {
        if (in.getPhotographerId() != null && !in.getPhotographerId().equals(me)) {
            throw ApiException.forbidden("You can only edit your own availability.");
        }
        in.setPhotographerId(me);
        if (in.getWeekly() == null) {
            in.setWeekly(new ArrayList<>());
        }
        if (in.getBlockedDates() == null) {
            in.setBlockedDates(new ArrayList<>());
        }
        if (in.getExceptions() == null) {
            in.setExceptions(new ArrayList<>());
        }
        if (in.getBlockedDates().size() > 400 || in.getExceptions().size() > 400) {
            throw ApiException.validation("Too many blocked dates or special days.");
        }
        AvailabilityEngine.validate(in);
        return repo.save(in);
    }

    public List<BusyInterval> busy(String photographerId, String ignoreBookingId) {
        List<BusyInterval> out = new ArrayList<>();
        for (Booking b : bookings.findByPhotographerIdAndStatusIn(photographerId, List.of("pending", "confirmed"))) {
            if (!b.getId().equals(ignoreBookingId)) {
                out.add(new BusyInterval(b.getDate(), b.getStartTime(), b.getEndTime()));
            }
        }
        return out;
    }

    private static void checkDuration(int minutes) {
        if (minutes < 15 || minutes > 1440) {
            throw ApiException.validation("Duration must be between 15 and 1440 minutes.", "duration", "15-1440");
        }
    }

    public List<Slot> slots(String photographerId, String date, int durationMinutes) {
        checkDuration(durationMinutes);
        Dates.parseDate(date);
        AvailabilityRules r = repo.findById(photographerId).orElse(null);
        return r == null ? List.of() : AvailabilityEngine.computeSlots(r, date, durationMinutes, busy(photographerId, null), clock.now(), clock.today());
    }

    public List<String> openDays(String photographerId, List<String> dates, int durationMinutes) {
        checkDuration(durationMinutes);
        if (dates.size() > 62) {
            throw ApiException.validation("Too many dates.");
        }
        AvailabilityRules r = repo.findById(photographerId).orElse(null);
        if (r == null) {
            return List.of();
        }
        List<BusyInterval> busy = busy(photographerId, null);
        List<String> out = new ArrayList<>();
        for (String d : dates) {
            Dates.parseDate(d);
            if (AvailabilityEngine.hasAnySlot(r, d, durationMinutes, busy, clock.now(), clock.today())) {
                out.add(d);
            }
        }
        return out;
    }
}
