package photography.photography.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import photography.photography.api.ApiException;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Values.BlockedRange;
import photography.photography.model.Values.BusyInterval;
import photography.photography.model.Values.DateException;
import photography.photography.model.Values.Slot;
import photography.photography.model.Values.TimeRange;

class AvailabilityEngineTest {
    // Monday 26 Oct 2026, 10:00
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDateTime NOW = TODAY.atTime(10, 0);

    private AvailabilityRules rules() {
        AvailabilityRules r = AvailabilityRules.defaults("p1");
        r.setSlotStepMinutes(60);
        r.setBufferBeforeMinutes(30);
        r.setBufferAfterMinutes(30);
        r.setMinNoticeHours(24);
        r.setMaxAdvanceDays(120);
        return r;
    }

    private List<Slot> slots(AvailabilityRules r, String date, int dur, List<BusyInterval> busy) {
        return AvailabilityEngine.computeSlots(r, date, dur, busy, NOW, TODAY);
    }

    @Test
    void weekendIsClosedAndWeekdayUsesBothRanges() {
        AvailabilityRules r = rules();
        assertTrue(slots(r, "2026-11-01", 60, List.of()).isEmpty(), "Sunday is off");
        assertTrue(slots(r, "2026-11-07", 60, List.of()).isEmpty(), "Saturday is off");
        List<Slot> mon = slots(r, "2026-11-02", 60, List.of());
        // 09-13 gives 4 hourly slots, 14:30-18:00 gives 3
        assertEquals(7, mon.size());
        assertEquals("09:00", mon.get(0).start());
        assertEquals("10:00", mon.get(0).end());
        assertEquals("14:30", mon.get(4).start());
        assertTrue(mon.stream().allMatch(Slot::available));
    }

    @Test
    void weekdayIndexUsesSundayAsZero() {
        assertEquals(0, Dates.weekday(LocalDate.of(2026, 11, 1)));
        assertEquals(1, Dates.weekday(LocalDate.of(2026, 11, 2)));
        assertEquals(6, Dates.weekday(LocalDate.of(2026, 11, 7)));
    }

    @Test
    void bookedSessionBlocksItselfAndItsBuffers() {
        AvailabilityRules r = rules();
        List<BusyInterval> busy = List.of(new BusyInterval("2026-11-02", "10:00", "11:00"));
        List<Slot> s = slots(r, "2026-11-02", 60, busy);
        // buffers: 09:30-11:30 is blocked. 09:00-10:00 overlaps (ends 10:00 > 09:30), 11:00-12:00 overlaps, 12:00-13:00 is free
        assertFalse(find(s, "09:00").available());
        assertEquals("booked", find(s, "09:00").reason());
        assertFalse(find(s, "10:00").available());
        assertFalse(find(s, "11:00").available());
        assertTrue(find(s, "12:00").available());
    }

    @Test
    void blockedDatesAndExceptions() {
        AvailabilityRules r = rules();
        r.setBlockedDates(new ArrayList<>(List.of(new BlockedRange("b1", "2026-11-02", "2026-11-03", "Trip"))));
        assertTrue(slots(r, "2026-11-02", 60, List.of()).isEmpty());
        assertTrue(slots(r, "2026-11-03", 60, List.of()).isEmpty());
        assertFalse(slots(r, "2026-11-04", 60, List.of()).isEmpty());
        // a special day replaces the weekly hours (open on a Sunday)
        r.setExceptions(new ArrayList<>(List.of(new DateException("2026-11-08", List.of(new TimeRange("10:00", "12:00")), "Special"))));
        List<Slot> sun = slots(r, "2026-11-08", 60, List.of());
        assertEquals(2, sun.size());
        assertEquals("10:00", sun.get(0).start());
    }

    @Test
    void minimumNoticePastAndHorizon() {
        AvailabilityRules r = rules();
        // today 09:00 is in the past, 14:30 is inside the 24 h notice window
        List<Slot> today = slots(r, "2026-10-26", 60, List.of());
        assertEquals("past", find(today, "09:00").reason());
        assertEquals("notice", find(today, "14:30").reason());
        // tomorrow 09:00 is only 23 h away
        assertEquals("notice", find(slots(r, "2026-10-27", 60, List.of()), "09:00").reason());
        assertTrue(find(slots(r, "2026-10-27", 60, List.of()), "12:00").available());
        // beyond the horizon everything is unavailable
        r.setMaxAdvanceDays(10);
        assertEquals("notice", find(slots(r, "2026-12-07", 60, List.of()), "09:00").reason());
    }

    @Test
    void ignoringTheClockStillDetectsConflicts() {
        AvailabilityRules r = rules();
        List<BusyInterval> busy = List.of(new BusyInterval("2026-10-27", "10:00", "11:00"));
        List<Slot> s = AvailabilityEngine.computeSlots(r, "2026-10-27", 60, busy, AvailabilityEngine.EPOCH, TODAY);
        assertTrue(find(s, "09:00").reason().equals("booked"));
        assertTrue(find(s, "12:00").available());
    }

    @Test
    void nextAvailableSkipsClosedDays() {
        AvailabilityRules r = rules();
        // From Saturday 7 Nov 09:00 both Saturday and Sunday are closed; Monday 9 Nov 09:00 is 48 h away, beyond the 24 h notice.
        String next = AvailabilityEngine.nextAvailableDate(r, 60, List.of(), LocalDate.of(2026, 11, 7).atTime(9, 0), LocalDate.of(2026, 11, 7));
        assertEquals("2026-11-09", next);
    }

    @Test
    void nothingAvailableWhenNoRanges() {
        AvailabilityRules r = rules();
        r.getWeekly().replaceAll(d -> new photography.photography.model.Values.DayHours(false, List.of()));
        assertNull(AvailabilityEngine.nextAvailableDate(r, 60, List.of(), NOW, TODAY));
    }

    @Test
    void validationRejectsBadRules() {
        AvailabilityRules r = rules();
        AvailabilityEngine.validate(r);
        r.setSlotStepMinutes(5);
        assertThrows(ApiException.class, () -> AvailabilityEngine.validate(r));
        AvailabilityRules r2 = rules();
        r2.getWeekly().set(1, new photography.photography.model.Values.DayHours(true, List.of(new TimeRange("13:00", "09:00"))));
        assertThrows(ApiException.class, () -> AvailabilityEngine.validate(r2));
        AvailabilityRules r3 = rules();
        r3.setWeekly(new ArrayList<>());
        assertThrows(ApiException.class, () -> AvailabilityEngine.validate(r3));
    }

    private static Slot find(List<Slot> s, String start) {
        return s.stream().filter(x -> x.start().equals(start)).findFirst().orElseThrow();
    }
}
