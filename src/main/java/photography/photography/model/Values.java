package photography.photography.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Small immutable value types shared by the Mongo documents and the REST contract.
 * Property names are identical to the Angular models in {@code src/app/core/models}.
 */
public final class Values {
    private Values() {
    }

    public record GeoPoint(double lat, double lng) {
    }

    public record GearItem(String kind, String name) {
    }

    public record CancellationPolicy(int freeCancelHours, int partialRefundPercent, int partialRefundHours, String summary) {
        public static CancellationPolicy standard() {
            return new CancellationPolicy(72, 50, 24, "Free cancellation up to 72 hours before. 50% refund until 24 hours before.");
        }
    }

    public record PortfolioImage(String id, String url, String caption, String categoryId, String albumId, int order, boolean visible, boolean isCover) {
    }

    public record Album(String id, String name, int order, boolean visible) {
    }

    public record AddOn(String id, String name, double price, String description) {
    }

    public record TimeRange(String start, String end) {
    }

    public record DayHours(boolean enabled, List<TimeRange> ranges) {
        public DayHours {
            ranges = ranges == null ? new ArrayList<>() : ranges;
        }
    }

    public record BlockedRange(String id, String start, String end, String reason) {
    }

    public record DateException(String date, List<TimeRange> ranges, String note) {
        public DateException {
            ranges = ranges == null ? new ArrayList<>() : ranges;
        }
    }

    public record PromoCode(String id, String code, int percent, boolean active, String expiresAt, int uses, Integer maxUses) {
    }

    public record PriceBreakdown(String pricingModel, double packagePrice, double addOnsTotal, double discount, String promoCode,
                                 double subtotal, double platformFee, double total, String currency, String quoteId) {
    }

    public record BookingEvent(Instant at, String actor, String type, String note) {
    }

    public record BookingLocation(String address, String city, GeoPoint point) {
    }

    public record AddOnLine(String id, String name, double price) {
    }

    public record RescheduleRequest(String date, String startTime, String requestedBy, Instant at) {
    }

    public record Cancellation(String by, String reason, Instant at, int refundPercent) {
    }

    public record Dispute(String state, String reason, Instant openedAt, String resolution) {
    }

    public record SavedLocation(String id, String label, String address, GeoPoint point) {
    }

    public record NotificationPrefs(boolean email, boolean push, boolean sms, boolean marketing) {
    }

    public record ApprovalEvent(Instant at, String actor, String action, String note) {
    }

    public record MessageTemplate(String id, String name, String body, String kind) {
    }

    public record Slot(String start, String end, boolean available, String reason) {
    }

    public record PriceInfo(Double amount, String currency, String model, String label) {
    }

    public record TimePoint(String label, double value) {
    }

    public record Series(String name, List<TimePoint> points) {
    }

    /** Busy interval used by the availability engine (a pending or confirmed booking). */
    public record BusyInterval(String date, String startTime, String endTime) {
    }

    public record FaqItem(String id, String q, String a) {
    }

    public record NamedTemplate(String id, String name, String body) {
    }

    public record CategoryDef(String id, String slug, String name, String icon, String image, boolean featured) {
    }

    /** Page envelope: matches the Angular {@code Page<T>}. */
    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
    }
}
