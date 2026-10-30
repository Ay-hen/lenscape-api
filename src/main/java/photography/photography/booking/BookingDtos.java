package photography.photography.booking;

import java.util.List;

import photography.photography.model.Values.BookingLocation;

public final class BookingDtos {
    private BookingDtos() {
    }

    public record QuoteRequest(String photographerId, String serviceId, String packageId, List<String> addOnIds, String promoCode, Double hours) {
    }

    public record CreateBookingRequest(String photographerId, String serviceId, String packageId, List<String> addOnIds, String promoCode, Double hours,
                                       String date, String startTime, String categoryId, BookingLocation location, String notes, Double expectedTotal,
                                       String quoteId, String idempotencyKey) {
    }

    public record StatusBody(String status, String note) {
    }

    public record CancelBody(String reason) {
    }

    public record RescheduleBody(String date, String startTime) {
    }

    public record RespondBody(boolean accept) {
    }

    public record DeliveryBody(String status) {
    }

    public record ReasonBody(String reason) {
    }

    public record ResolutionBody(String resolution) {
    }

    public record RefundBody(String kind) {
    }

    public record ReviewBody(int rating, String comment) {
    }

    public record ReplyBody(String text) {
    }

    public record BookingQuery(String status, String q, String from, String to, String photographerId, String customerId, Integer page, Integer pageSize) {
    }
}
