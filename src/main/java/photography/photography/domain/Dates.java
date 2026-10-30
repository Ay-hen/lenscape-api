package photography.photography.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import photography.photography.api.ApiException;

/** Dates are plain local strings: 'YYYY-MM-DD' and 'HH:mm' in the platform time zone (see app.timezone). */
public final class Dates {
    private Dates() {
    }

    public static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.validation("Invalid date. Use YYYY-MM-DD.", "date", "Invalid date");
        }
    }

    public static int timeToMinutes(String t) {
        try {
            LocalTime lt = LocalTime.parse(t);
            return lt.getHour() * 60 + lt.getMinute();
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.validation("Invalid time. Use HH:mm.", "startTime", "Invalid time");
        }
    }

    public static String minutesToTime(int min) {
        int m = ((min % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    public static LocalDateTime combine(String date, String time) {
        return parseDate(date).atStartOfDay().plusMinutes(timeToMinutes(time));
    }

    /** 0 = Sunday ... 6 = Saturday, the convention used by JavaScript's Date.getDay() and by AvailabilityRules.weekly. */
    public static int weekday(LocalDate d) {
        return d.getDayOfWeek().getValue() % 7;
    }

    public static boolean isIsoDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    public static boolean isTime(String s) {
        try {
            LocalTime.parse(s);
            return s.length() == 5;
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }
}
