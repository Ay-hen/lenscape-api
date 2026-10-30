package photography.photography.model;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;
import photography.photography.model.Values.BlockedRange;
import photography.photography.model.Values.DateException;
import photography.photography.model.Values.DayHours;
import photography.photography.model.Values.TimeRange;

/** Weekly hours + exceptions of one photographer. {@code weekly[0]} is Sunday (same as JavaScript's Date.getDay()). */
@Data
@NoArgsConstructor
@Document("availability")
public class AvailabilityRules {
    @Id
    private String photographerId;
    private List<DayHours> weekly = new ArrayList<>();
    private int slotStepMinutes = 60;
    private int bufferBeforeMinutes = 30;
    private int bufferAfterMinutes = 30;
    private int minNoticeHours = 24;
    private int maxAdvanceDays = 120;
    private List<BlockedRange> blockedDates = new ArrayList<>();
    private List<DateException> exceptions = new ArrayList<>();

    /** Mon-Fri 09:00-13:00 and 14:30-18:00, weekends off: the starting point for a new photographer. */
    public static AvailabilityRules defaults(String photographerId) {
        AvailabilityRules r = new AvailabilityRules();
        r.photographerId = photographerId;
        DayHours off = new DayHours(false, new ArrayList<>());
        DayHours day = new DayHours(true, new ArrayList<>(List.of(new TimeRange("09:00", "13:00"), new TimeRange("14:30", "18:00"))));
        r.weekly = new ArrayList<>(List.of(off, day, day, day, day, day, off));
        return r;
    }
}
