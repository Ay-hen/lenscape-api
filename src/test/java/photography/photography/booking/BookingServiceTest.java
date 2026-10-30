package photography.photography.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import photography.photography.admin.AuditService;
import photography.photography.admin.SettingsService;
import photography.photography.api.ApiException;
import photography.photography.api.KeyedLock;
import photography.photography.api.PlatformClock;
import photography.photography.booking.BookingDtos.CreateBookingRequest;
import photography.photography.catalog.AvailabilityService;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Booking;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Misc.Counter;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.User;
import photography.photography.model.Values.BookingLocation;
import photography.photography.model.Values.PriceBreakdown;
import photography.photography.notification.Notifier;
import photography.photography.respository.AvailabilityRepo;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.MiscRepos.AutomationRepo;
import photography.photography.respository.MiscRepos.PromoRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ServiceOfferRepo;
import photography.photography.respository.ServicePackageRepo;
import photography.photography.respository.UserRepo;

class BookingServiceTest {
    BookingRepo bookings = mock(BookingRepo.class);
    ServiceOfferRepo services = mock(ServiceOfferRepo.class);
    ServicePackageRepo packages = mock(ServicePackageRepo.class);
    PhotographerProfileRepo profiles = mock(PhotographerProfileRepo.class);
    PromoRepo promos = mock(PromoRepo.class);
    AutomationRepo automation = mock(AutomationRepo.class);
    UserRepo users = mock(UserRepo.class);
    AvailabilityRepo availabilityRepo = mock(AvailabilityRepo.class);
    SettingsService settings = mock(SettingsService.class);
    AuditService audit = mock(AuditService.class);
    Notifier notifier = mock(Notifier.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    PlatformClock clock = new PlatformClock("Africa/Casablanca");
    BookingService service;

    User alice = user("alice", "Alice", "client");
    User eve = user("eve", "Eve", "client");
    User bob = user("bob", "Bob", "photographer");
    User admin = user("root", "Root", "admin");
    String date;
    final List<Booking> store = new ArrayList<>();

    static User user(String username, String name, String role) {
        return User.builder().username(username).name(name).role(role).suspended(false).build();
    }

    static LocalDate weekday(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    @BeforeEach
    void setUp() {
        AvailabilityService availability = new AvailabilityService(availabilityRepo, bookings, clock);
        service = new BookingService(bookings, services, packages, profiles, promos, automation, users, availability, settings, audit, notifier, clock, new KeyedLock(), mongo);

        ServiceOffer svc = new ServiceOffer();
        svc.setId("s1");
        svc.setPhotographerId("bob");
        svc.setTitle("Wedding day");
        svc.setCategoryId("wedding");
        svc.setStatus("published");
        svc.setPricingModel("fixed");
        ServicePackage pk = new ServicePackage();
        pk.setId("k1");
        pk.setServiceId("s1");
        pk.setPhotographerId("bob");
        pk.setName("Gold");
        pk.setTier("gold");
        pk.setPrice(1500.0);
        pk.setDurationMinutes(120);
        pk.setStatus("published");
        PhotographerProfile ph = new PhotographerProfile();
        ph.setId("bob");
        ph.setDisplayName("Bob Studio");
        ph.setApproval("approved");
        PlatformSettings ps = new PlatformSettings();
        ps.setCommissionPercent(10);

        when(services.findById("s1")).thenReturn(Optional.of(svc));
        when(packages.findById("k1")).thenReturn(Optional.of(pk));
        when(profiles.findById("bob")).thenReturn(Optional.of(ph));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(settings.get()).thenReturn(ps);
        when(availabilityRepo.findById("bob")).thenReturn(Optional.of(AvailabilityRules.defaults("bob")));
        when(promos.findById(anyString())).thenReturn(Optional.empty());
        when(automation.findById(anyString())).thenReturn(Optional.empty());
        when(bookings.findById(anyString())).thenAnswer(i -> store.stream().filter(b -> b.getId().equals(i.getArgument(0))).findFirst());
        when(bookings.findByPhotographerIdAndStatusIn(anyString(), any())).thenAnswer(i -> store.stream().filter(b -> List.of("pending", "confirmed").contains(b.getStatus())).toList());
        when(bookings.insert(any(Booking.class))).thenAnswer(i -> {
            store.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(bookings.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        Counter c = new Counter();
        c.setValue(5);
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(Class.class), anyString())).thenReturn(c);
        date = weekday(clock.today().plusDays(14)).toString();
    }

    CreateBookingRequest request(String key, double expected) {
        return new CreateBookingRequest("bob", "s1", "k1", List.of(), null, null, date, "10:00", "wedding", new BookingLocation("1 Rue X", "Casablanca", null), "Hello", expected, null, key);
    }

    @Test
    void createsPendingBookingWithServerSidePriceAndHidesFeeFromCustomer() {
        Booking b = service.create(alice, request("key-aaaaaaaa", 1500));
        assertEquals("pending", b.getStatus());
        assertEquals("LS-1105", b.getReference());
        assertEquals("12:00", b.getEndTime());
        assertEquals(1500, b.getPricing().total());
        assertEquals(0, b.getPricing().platformFee(), "customers never see the commission");
        assertEquals("wedding", b.getCategoryId());
        assertEquals("unpaid", b.getPaymentStatus());
        verify(notifier, times(2)).notify(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void refusesWhenThePriceChangedUnderTheCustomer() {
        ApiException e = assertThrows(ApiException.class, () -> service.create(alice, request("key-bbbbbbbb", 1200)));
        assertEquals("PRICE_CHANGED", e.code());
        verify(bookings, never()).insert(any(Booking.class));
    }

    @Test
    void refusesASlotThatIsAlreadyTaken() {
        service.create(alice, request("key-cccccccc", 1500));
        ApiException e = assertThrows(ApiException.class, () -> service.create(eve, request("key-dddddddd", 1500)));
        assertEquals("SLOT_UNAVAILABLE", e.code());
        assertEquals(1, store.size());
    }

    @Test
    void retryReturnsTheSameBookingButNeverSomeoneElses() {
        Booking first = service.create(alice, request("key-eeeeeeee", 1500));
        Booking again = service.create(alice, request("key-eeeeeeee", 1500));
        assertEquals(first.getId(), again.getId());
        assertEquals(1, store.size());
        assertEquals("CONFLICT", assertThrows(ApiException.class, () -> service.create(eve, request("key-eeeeeeee", 1500))).code());
    }

    @Test
    void onlyCustomersCanBookAndRequestIdsAreValidated() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.create(bob, request("key-ffffffff", 1500))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.create(alice, request("x", 1500))).status());
    }

    @Test
    void autoAcceptConfirmsWhenSafeguardsMatchAndRespectsThePriceCeiling() {
        AutomationSettings a = new AutomationSettings();
        a.setAutoAccept(new AutomationSettings.AutoAccept(true, 12, 3000.0, false));
        when(automation.findById("bob")).thenReturn(Optional.of(a));
        assertEquals("confirmed", service.create(alice, request("key-gggggggg", 1500)).getStatus());

        AutomationSettings low = new AutomationSettings();
        low.setAutoAccept(new AutomationSettings.AutoAccept(true, 12, 1000.0, false));
        when(automation.findById("bob")).thenReturn(Optional.of(low));
        store.clear();
        assertEquals("pending", service.create(alice, request("key-hhhhhhhh", 1500)).getStatus());
    }

    @Test
    void customersCannotSeeOrChangeOthersBookings() {
        Booking b = service.create(alice, request("key-iiiiiiii", 1500));
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(b.getId(), eve)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.setStatus(b.getId(), "confirmed", null, alice)).status());
        assertEquals("pending", service.get(b.getId(), admin).getStatus());
    }

    @Test
    void photographerConfirmsThenCompletionNeedsThePastDate() {
        Booking b = service.create(alice, request("key-jjjjjjjj", 1500));
        assertEquals("confirmed", service.setStatus(b.getId(), "confirmed", null, bob).getStatus());
        assertEquals("CONFLICT", assertThrows(ApiException.class, () -> service.setStatus(b.getId(), "completed", null, bob)).code(), "session is in the future");
        assertEquals(409, assertThrows(ApiException.class, () -> service.setStatus(b.getId(), "confirmed", null, bob)).status(), "already confirmed");
        b.setDate(clock.today().minusDays(1).toString());
        assertEquals("completed", service.setStatus(b.getId(), "completed", null, bob).getStatus());
        verify(profiles).incrementCompleted("bob");
    }

    @Test
    void cancellationRefundsFollowThePolicy() {
        Booking far = service.create(alice, request("key-kkkkkkkk", 1500));
        service.setStatus(far.getId(), "confirmed", null, bob);
        assertEquals(100, service.cancel(far.getId(), "plans changed", alice).getCancellation().refundPercent());
        assertEquals("cancelled", far.getStatus());
        assertEquals(409, assertThrows(ApiException.class, () -> service.cancel(far.getId(), "again", alice)).status());

        Booking near = new Booking();
        near.setId("near-booking");
        near.setCustomerId("alice");
        near.setPhotographerId("bob");
        near.setPhotographerName("Bob Studio");
        near.setStatus("confirmed");
        near.setDate(clock.today().toString());
        near.setStartTime("00:00");
        near.setDurationMinutes(60);
        near.setHistory(new ArrayList<>());
        store.add(near);
        assertEquals(0, service.cancel("near-booking", "late", alice).getCancellation().refundPercent(), "inside the partial-refund window");
    }

    @Test
    void theRequesterCannotAnswerItsOwnRescheduleRequest() {
        Booking b = service.create(alice, request("key-llllllll", 1500));
        LocalDate other = weekday(LocalDate.parse(date).plusDays(1));
        service.requestReschedule(b.getId(), other.toString(), "14:30", alice);
        assertEquals(403, assertThrows(ApiException.class, () -> service.respondReschedule(b.getId(), true, alice)).status());
        Booking moved = service.respondReschedule(b.getId(), true, bob);
        assertEquals(other.toString(), moved.getDate());
        assertEquals("14:30", moved.getStartTime());
        assertEquals("16:30", moved.getEndTime());
        assertNull(moved.getRescheduleRequest());
    }

    @Test
    void quoteRejectsUnknownPromoCodes() {
        PriceBreakdown p = service.quote(new BookingDtos.QuoteRequest("bob", "s1", "k1", List.of(), null, null));
        assertEquals(1500, p.total());
        assertEquals(400, assertThrows(ApiException.class, () -> service.quote(new BookingDtos.QuoteRequest("bob", "s1", "k1", List.of(), "NOPE", null))).status());
    }
}
