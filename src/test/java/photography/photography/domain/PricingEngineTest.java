package photography.photography.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import photography.photography.api.ApiException;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.Values.AddOn;
import photography.photography.model.Values.CancellationPolicy;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.PriceBreakdown;
import photography.photography.model.Values.PriceInfo;
import photography.photography.model.Values.PromoCode;

class PricingEngineTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private ServiceOffer svc(String model) {
        ServiceOffer s = new ServiceOffer();
        s.setId("s1");
        s.setPhotographerId("p1");
        s.setPricingModel(model);
        s.setStatus("published");
        s.setAddOns(List.of(new AddOn("a1", "Extra hour", 300, ""), new AddOn("a2", "Album", 200, "")));
        return s;
    }

    private ServicePackage pkg(Double price, int minutes) {
        ServicePackage p = new ServicePackage();
        p.setId("k1");
        p.setServiceId("s1");
        p.setPhotographerId("p1");
        p.setPrice(price);
        p.setDurationMinutes(minutes);
        p.setStatus("published");
        return p;
    }

    @Test
    void fixedPriceWithAddOns() {
        PriceBreakdown b = PricingEngine.price(svc("fixed"), pkg(1500.0, 120), List.of("a1", "a2", "nope"), null, null, List.of(), 10, TODAY);
        assertEquals(1500, b.packagePrice());
        assertEquals(500, b.addOnsTotal());
        assertEquals(2000, b.total());
        assertEquals(2000, b.subtotal());
        assertEquals(200, b.platformFee());
        assertEquals("q-k1-200000", b.quoteId());
    }

    @Test
    void hourlyUsesHoursOrPackageDuration() {
        assertEquals(450, PricingEngine.price(svc("hourly"), pkg(150.0, 180), List.of(), null, null, List.of(), 10, TODAY).total());
        assertEquals(225, PricingEngine.price(svc("hourly"), pkg(150.0, 180), List.of(), null, 1.5, List.of(), 10, TODAY).total());
        assertThrows(ApiException.class, () -> PricingEngine.price(svc("hourly"), pkg(150.0, 180), List.of(), null, -3.0, List.of(), 10, TODAY));
        assertThrows(ApiException.class, () -> PricingEngine.price(svc("hourly"), pkg(150.0, 180), List.of(), null, 0.0, List.of(), 10, TODAY));
        assertThrows(ApiException.class, () -> PricingEngine.price(svc("hourly"), pkg(150.0, 180), List.of(), null, 99.0, List.of(), 10, TODAY));
    }

    @Test
    void customQuoteHasNoBasePrice() {
        PriceBreakdown b = PricingEngine.price(svc("quote"), pkg(null, 120), List.of("a2"), null, null, List.of(), 10, TODAY);
        assertEquals(0, b.packagePrice());
        assertEquals(200, b.total());
    }

    @Test
    void promoCodeRules() {
        PromoCode ok = new PromoCode("c1", "WELCOME10", 10, true, null, 0, 5);
        PriceBreakdown b = PricingEngine.price(svc("fixed"), pkg(1000.0, 60), List.of(), " welcome10 ", null, List.of(ok), 10, TODAY);
        assertEquals(100, b.discount());
        assertEquals(900, b.total());
        assertEquals("WELCOME10", b.promoCode());
        assertEquals(90, b.platformFee());
        // inactive, exhausted, expired, unknown
        for (PromoCode bad : List.of(new PromoCode("c", "X", 10, false, null, 0, null), new PromoCode("c", "X", 10, true, null, 5, 5),
                new PromoCode("c", "X", 10, true, "2026-10-25", 0, null))) {
            ApiException e = assertThrows(ApiException.class, () -> PricingEngine.price(svc("fixed"), pkg(1000.0, 60), List.of(), "X", null, List.of(bad), 10, TODAY));
            assertEquals("VALIDATION", e.code());
        }
        assertThrows(ApiException.class, () -> PricingEngine.price(svc("fixed"), pkg(1000.0, 60), List.of(), "NOPE", null, List.of(ok), 10, TODAY));
        // still valid on the expiry day
        PricingEngine.price(svc("fixed"), pkg(1000.0, 60), List.of(), "X", null, List.of(new PromoCode("c", "X", 10, true, "2026-10-26", 0, null)), 10, TODAY);
    }

    @Test
    void unavailableOfferingsAreRejected() {
        ServiceOffer hidden = svc("fixed");
        hidden.setModeration("hidden");
        assertEquals("CONFLICT", assertThrows(ApiException.class, () -> PricingEngine.price(hidden, pkg(100.0, 60), List.of(), null, null, List.of(), 10, TODAY)).code());
        ServiceOffer draft = svc("fixed");
        draft.setStatus("draft");
        assertThrows(ApiException.class, () -> PricingEngine.price(draft, pkg(100.0, 60), List.of(), null, null, List.of(), 10, TODAY));
        ServicePackage archived = pkg(100.0, 60);
        archived.setStatus("archived");
        assertThrows(ApiException.class, () -> PricingEngine.price(svc("fixed"), archived, List.of(), null, null, List.of(), 10, TODAY));
    }

    @Test
    void refundPolicy() {
        CancellationPolicy p = new CancellationPolicy(72, 50, 24, "");
        assertEquals(100, PricingEngine.refundPercent("client", "confirmed", 100, p));
        assertEquals(100, PricingEngine.refundPercent("client", "confirmed", 72, p));
        assertEquals(50, PricingEngine.refundPercent("client", "confirmed", 30, p));
        assertEquals(50, PricingEngine.refundPercent("client", "confirmed", 24, p));
        assertEquals(0, PricingEngine.refundPercent("client", "confirmed", 5, p));
        assertEquals(100, PricingEngine.refundPercent("client", "pending", 1, p), "unconfirmed requests refund fully");
        assertEquals(100, PricingEngine.refundPercent("photographer", "confirmed", 1, p));
        assertEquals(100, PricingEngine.refundPercent("admin", "confirmed", 1, p));
    }

    @Test
    void startingPriceNeverMixesModels() {
        ServiceOffer fixed = svc("fixed");
        ServiceOffer hourly = svc("hourly");
        hourly.setId("s2");
        ServicePackage a = pkg(900.0, 60);
        ServicePackage b = pkg(120.0, 60);
        b.setId("k2");
        b.setServiceId("s2");
        PriceInfo mixed = PricingEngine.startingPrice(List.of(fixed, hourly), List.of(a, b));
        assertEquals(900, mixed.amount());
        assertEquals("from", mixed.label());
        PriceInfo hourlyOnly = PricingEngine.startingPrice(List.of(hourly), List.of(b));
        assertEquals(120, hourlyOnly.amount());
        assertEquals("per_hour", hourlyOnly.label());
        PriceInfo quote = PricingEngine.startingPrice(List.of(svc("quote")), List.of());
        assertNull(quote.amount());
        assertEquals("quote", quote.label());
        ServiceOffer hiddenSvc = svc("fixed");
        hiddenSvc.setModeration("hidden");
        assertNull(PricingEngine.startingPrice(List.of(hiddenSvc), List.of(a)).amount());
    }

    @Test
    void geoHelpers() {
        double km = Geo.haversineKm(Geo.CITY_COORDS.get("Casablanca"), Geo.CITY_COORDS.get("Rabat"));
        assertTrue(km > 80 && km < 95, "Casablanca-Rabat is about 87 km, got " + km);
        GeoPoint c = Geo.CITY_COORDS.get("Casablanca");
        GeoPoint a1 = Geo.approximate(c, "alice");
        assertEquals(a1, Geo.approximate(c, "alice"), "deterministic");
        double offset = Geo.haversineKm(c, a1);
        assertTrue(offset > 0.1 && offset < 0.7, "offset must stay within a few hundred metres, got " + offset);
        assertEquals("Casablanca", Geo.canonicalCity(" casablanca "));
        assertNull(Geo.canonicalCity("Atlantis"));
    }
}
