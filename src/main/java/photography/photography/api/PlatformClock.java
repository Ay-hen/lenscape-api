package photography.photography.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** "Now" and "today" in the platform time zone (app.timezone). Booking dates/times are wall-clock values in that zone. */
@Component
public class PlatformClock {
    private final ZoneId zone;

    public PlatformClock(@Value("${app.timezone:Africa/Casablanca}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public Instant instant() {
        return Instant.now();
    }
}
