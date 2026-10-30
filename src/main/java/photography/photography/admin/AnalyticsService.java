package photography.photography.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.api.PlatformClock;
import photography.photography.domain.Dates;
import photography.photography.model.Booking;
import photography.photography.model.Misc.ViewStat;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.User;
import photography.photography.model.Values.Series;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.ViewStatRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.UserRepo;

/** Charts computed from real bookings, users and profile-view events. */
@Service
public class AnalyticsService {
    private final BookingRepo bookings;
    private final UserRepo users;
    private final PhotographerProfileRepo profiles;
    private final ViewStatRepo views;
    private final PlatformClock clock;

    public AnalyticsService(BookingRepo bookings, UserRepo users, PhotographerProfileRepo profiles, ViewStatRepo views, PlatformClock clock) {
        this.bookings = bookings;
        this.users = users;
        this.profiles = profiles;
        this.views = views;
        this.clock = clock;
    }

    public record NamedValue(String label, double value) {
    }

    public record TopPhotographer(String name, int bookings, double revenue, double rating) {
    }

    public record PlatformBundle(Series bookingsOverTime, Series revenueOverTime, Series cancellationsOverTime, Series customerAcquisition,
                                 List<NamedValue> popularServices, List<TopPhotographer> topPhotographers, List<NamedValue> conversion) {
    }

    public record PhotographerBundle(Series revenue, Series bookings, Series views, double conversionRate, long views30d, long requests30d) {
    }

    private static boolean live(Booking b) {
        return "confirmed".equals(b.getStatus()) || "completed".equals(b.getStatus());
    }

    private static LocalDate[] range(String from, String to) {
        LocalDate a = Dates.parseDate(from);
        LocalDate b = Dates.parseDate(to);
        if (a.isAfter(b) || a.plusMonths(36).isBefore(b)) {
            throw ApiException.validation("Choose a date range of at most 36 months.");
        }
        return new LocalDate[] {a, b};
    }

    public PlatformBundle platform(String from, String to) {
        LocalDate[] r = range(from, to);
        List<Booking> bk = bookings.findAll().stream().filter(b -> b.getDate().compareTo(from) >= 0 && b.getDate().compareTo(to) <= 0).toList();
        Map<String, Integer> cnt = new HashMap<>();
        bk.forEach(b -> cnt.merge(b.getServiceTitle(), 1, Integer::sum));
        List<NamedValue> popular = cnt.entrySet().stream().map(e -> new NamedValue(e.getKey(), e.getValue())).sorted(Comparator.comparingDouble(NamedValue::value).reversed()).limit(6).toList();
        List<TopPhotographer> top = new ArrayList<>();
        for (PhotographerProfile p : profiles.findByApproval("approved")) {
            List<Booking> mine = bk.stream().filter(b -> b.getPhotographerId().equals(p.getId()) && live(b)).toList();
            top.add(new TopPhotographer(p.getDisplayName(), mine.size(), mine.stream().mapToDouble(b -> b.getPricing().total()).sum(), p.getRating()));
        }
        top.sort(Comparator.comparingDouble(TopPhotographer::revenue).reversed());
        List<User> clients = users.findByRole("client").stream().filter(u -> u.getCreatedAt() != null).toList();
        return new PlatformBundle(SeriesUtil.count("Bookings", r[0], r[1], bk, Booking::getDate),
                SeriesUtil.series("Revenue (MAD)", r[0], r[1], bk.stream().filter(AnalyticsService::live).toList(), Booking::getDate, b -> b.getPricing().total()),
                SeriesUtil.count("Cancellations", r[0], r[1], bk.stream().filter(b -> "cancelled".equals(b.getStatus())).toList(), Booking::getDate),
                SeriesUtil.count("New customers", r[0], r[1], clients, u -> u.getCreatedAt().atZone(clock.zone()).toLocalDate().toString()),
                popular, top.stream().limit(8).toList(),
                List.of(new NamedValue("Requested", bk.size()), new NamedValue("Confirmed", bk.stream().filter(AnalyticsService::live).count()),
                        new NamedValue("Completed", bk.stream().filter(b -> "completed".equals(b.getStatus())).count())));
    }

    public PhotographerBundle photographer(String pid, String from, String to, User me) {
        if (!"admin".equals(me.getRole()) && !pid.equals(me.getUsername())) {
            throw ApiException.forbidden("Not your analytics");
        }
        profiles.findById(pid).orElseThrow(() -> ApiException.notFound("Not found"));
        LocalDate[] r = range(from, to);
        List<Booking> bk = bookings.findByPhotographerId(pid).stream().filter(b -> b.getDate().compareTo(from) >= 0 && b.getDate().compareTo(to) <= 0).toList();
        List<ViewStat> vs = views.findByPhotographerIdAndDayBetween(pid, from, to);
        String since = clock.today().minusDays(30).toString();
        long views30 = views.findByPhotographerIdAndDayBetween(pid, since, clock.today().toString()).stream().mapToLong(ViewStat::getCount).sum();
        long requests30 = bookings.findByPhotographerId(pid).stream().filter(b -> b.getDate().compareTo(since) >= 0).count();
        long accepted = bk.stream().filter(AnalyticsService::live).count();
        return new PhotographerBundle(SeriesUtil.series("Revenue", r[0], r[1], bk.stream().filter(AnalyticsService::live).toList(), Booking::getDate, b -> b.getPricing().total()),
                SeriesUtil.count("Bookings", r[0], r[1], bk, Booking::getDate),
                SeriesUtil.series("Profile views", r[0], r[1], vs, ViewStat::getDay, ViewStat::getCount),
                bk.isEmpty() ? 0 : (double) accepted / bk.size(), views30, requests30);
    }
}
