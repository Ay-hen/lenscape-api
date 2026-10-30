package photography.photography.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import photography.photography.model.AvailabilityRules;
import photography.photography.model.Values.BusyInterval;
import photography.photography.model.Values.DateException;
import photography.photography.model.Values.DayHours;
import photography.photography.model.Values.Slot;
import photography.photography.model.Values.TimeRange;

/**
 * Authoritative slot computation (the server decides what is bookable; the browser only displays it).
 * Reasons: "booked", "past", "notice" (inside the minimum-notice window or beyond the booking horizon).
 */
public final class AvailabilityEngine {
    private AvailabilityEngine() {
    }

    public static boolean isBlocked(AvailabilityRules rules, String date) {
        return rules.getBlockedDates().stream().anyMatch(b -> date.compareTo(b.start()) >= 0 && date.compareTo(b.end()) <= 0);
    }

    public static List<TimeRange> rangesForDate(AvailabilityRules rules, String date) {
        if (isBlocked(rules, date)) {
            return List.of();
        }
        for (DateException ex : rules.getExceptions()) {
            if (date.equals(ex.date())) {
                return ex.ranges();
            }
        }
        List<DayHours> weekly = rules.getWeekly();
        int idx = Dates.weekday(Dates.parseDate(date));
        DayHours day = idx < weekly.size() ? weekly.get(idx) : null;
        return day != null && day.enabled() ? day.ranges() : List.of();
    }

    /**
     * @param now   the instant the clock rules ("past", "notice") are evaluated against; pass {@link #EPOCH} to ignore the clock
     *              (used when re-checking an existing booking against conflicts only)
     * @param today the current local date, used for the booking horizon (maxAdvanceDays)
     */
    public static List<Slot> computeSlots(AvailabilityRules rules, String date, int durationMinutes, List<BusyInterval> busy,
                                          LocalDateTime now, LocalDate today) {
        List<Slot> slots = new ArrayList<>();
        if (durationMinutes <= 0 || rules.getSlotStepMinutes() <= 0) {
            return slots;
        }
        boolean beyond = ChronoUnit.DAYS.between(today, Dates.parseDate(date)) > rules.getMaxAdvanceDays();
        List<int[]> dayBusy = new ArrayList<>();
        for (BusyInterval b : busy) {
            if (date.equals(b.date())) {
                dayBusy.add(new int[] {Dates.timeToMinutes(b.startTime()) - rules.getBufferBeforeMinutes(), Dates.timeToMinutes(b.endTime()) + rules.getBufferAfterMinutes()});
            }
        }
        LocalDateTime earliest = now.plusMinutes(rules.getMinNoticeHours() * 60L);
        for (TimeRange r : rangesForDate(rules, date)) {
            int rs = Dates.timeToMinutes(r.start());
            int re = Dates.timeToMinutes(r.end());
            for (int s = rs; s + durationMinutes <= re; s += rules.getSlotStepMinutes()) {
                int e = s + durationMinutes;
                String start = Dates.minutesToTime(s);
                String end = Dates.minutesToTime(e);
                LocalDateTime at = Dates.combine(date, start);
                String reason = null;
                if (beyond) {
                    reason = "notice";
                } else if (at.isBefore(now)) {
                    reason = "past";
                } else if (at.isBefore(earliest)) {
                    reason = "notice";
                } else {
                    for (int[] b : dayBusy) {
                        if (s < b[1] && e > b[0]) {
                            reason = "booked";
                            break;
                        }
                    }
                }
                slots.add(new Slot(start, end, reason == null, reason));
            }
        }
        return slots;
    }

    public static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    public static boolean hasAnySlot(AvailabilityRules rules, String date, int durationMinutes, List<BusyInterval> busy, LocalDateTime now, LocalDate today) {
        return computeSlots(rules, date, durationMinutes, busy, now, today).stream().anyMatch(Slot::available);
    }

    /** First date (within 90 days / the booking horizon) that still has a free slot, or null. */
    public static String nextAvailableDate(AvailabilityRules rules, int durationMinutes, List<BusyInterval> busy, LocalDateTime now, LocalDate today) {
        int horizon = Math.min(rules.getMaxAdvanceDays(), 90);
        for (int i = 0; i <= horizon; i++) {
            String d = today.plusDays(i).toString();
            if (hasAnySlot(rules, d, durationMinutes, busy, now, today)) {
                return d;
            }
        }
        return null;
    }

    /** Validates rules submitted by a photographer. */
    public static void validate(AvailabilityRules rules) {
        if (rules.getWeekly() == null || rules.getWeekly().size() != 7) {
            throw photography.photography.api.ApiException.validation("Weekly hours must describe all 7 days.");
        }
        for (DayHours d : rules.getWeekly()) {
            for (TimeRange r : d.ranges()) {
                if (!Dates.isTime(r.start()) || !Dates.isTime(r.end()) || r.start().compareTo(r.end()) >= 0) {
                    throw photography.photography.api.ApiException.validation("Each time range must end after it starts.");
                }
            }
        }
        for (DateException ex : rules.getExceptions()) {
            if (!Dates.isIsoDate(ex.date())) {
                throw photography.photography.api.ApiException.validation("Invalid special-day date.");
            }
            for (TimeRange r : ex.ranges()) {
                if (!Dates.isTime(r.start()) || !Dates.isTime(r.end()) || r.start().compareTo(r.end()) >= 0) {
                    throw photography.photography.api.ApiException.validation("Each time range must end after it starts.");
                }
            }
        }
        rules.getBlockedDates().forEach(b -> {
            if (!Dates.isIsoDate(b.start()) || !Dates.isIsoDate(b.end()) || b.start().compareTo(b.end()) > 0) {
                throw photography.photography.api.ApiException.validation("Invalid blocked date range.");
            }
        });
        if (rules.getSlotStepMinutes() < 15 || rules.getSlotStepMinutes() > 240) {
            throw photography.photography.api.ApiException.validation("Slot step must be between 15 and 240 minutes.");
        }
        if (rules.getBufferBeforeMinutes() < 0 || rules.getBufferAfterMinutes() < 0 || rules.getBufferBeforeMinutes() > 480 || rules.getBufferAfterMinutes() > 480) {
            throw photography.photography.api.ApiException.validation("Buffers must be between 0 and 480 minutes.");
        }
        if (rules.getMinNoticeHours() < 0 || rules.getMinNoticeHours() > 24 * 60) {
            throw photography.photography.api.ApiException.validation("Minimum notice is out of range.");
        }
        if (rules.getMaxAdvanceDays() < 1 || rules.getMaxAdvanceDays() > 730) {
            throw photography.photography.api.ApiException.validation("Booking horizon must be between 1 and 730 days.");
        }
    }
}
