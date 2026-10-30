package photography.photography.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import photography.photography.model.Values.Series;
import photography.photography.model.Values.TimePoint;

/** Monthly bucketing for charts. Items are grouped by the month of their 'YYYY-MM-DD' date. */
public final class SeriesUtil {
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private SeriesUtil() {
    }

    /** Buckets from the month of {@code from} to the month of {@code to}, at most 36. Key is 'YYYY-MM'. */
    public static Map<String, String> buckets(LocalDate from, LocalDate to) {
        Map<String, String> out = new LinkedHashMap<>();
        LocalDate d = from.withDayOfMonth(1);
        while (!d.isAfter(to) && out.size() < 36) {
            out.put(String.format("%d-%02d", d.getYear(), d.getMonthValue()), MONTHS[d.getMonthValue() - 1]);
            d = d.plusMonths(1);
        }
        return out;
    }

    public static <T> Series series(String name, LocalDate from, LocalDate to, List<T> items, java.util.function.Function<T, String> date, ToDoubleFunction<T> value) {
        Map<String, String> b = buckets(from, to);
        Map<String, Double> sums = new LinkedHashMap<>();
        b.keySet().forEach(k -> sums.put(k, 0.0));
        for (T i : items) {
            String d = date.apply(i);
            String k = d == null || d.length() < 7 ? "" : d.substring(0, 7);
            if (sums.containsKey(k)) {
                sums.put(k, sums.get(k) + value.applyAsDouble(i));
            }
        }
        List<TimePoint> pts = new ArrayList<>();
        b.forEach((k, label) -> pts.add(new TimePoint(label, sums.get(k))));
        return new Series(name, pts);
    }

    public static <T> Series count(String name, LocalDate from, LocalDate to, List<T> items, java.util.function.Function<T, String> date) {
        return series(name, from, to, items, date, x -> 1);
    }
}
