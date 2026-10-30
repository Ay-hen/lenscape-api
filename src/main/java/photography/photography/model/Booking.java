package photography.photography.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;
import photography.photography.model.Values.AddOnLine;
import photography.photography.model.Values.BookingEvent;
import photography.photography.model.Values.BookingLocation;
import photography.photography.model.Values.Cancellation;
import photography.photography.model.Values.Dispute;
import photography.photography.model.Values.PriceBreakdown;
import photography.photography.model.Values.RescheduleRequest;

/** A booking request / booking. The Angular {@code Booking}. {@code id} is the client's idempotency key. */
@Data
@NoArgsConstructor
@Document("bookings")
@CompoundIndex(name = "by_photographer_date", def = "{'photographerId': 1, 'date': 1}")
@CompoundIndex(name = "by_customer_date", def = "{'customerId': 1, 'date': -1}")
public class Booking {
    @Id
    private String id;
    @Indexed(unique = true)
    private String reference;
    private String customerId;
    private String customerName;
    private String photographerId;
    private String photographerName;
    private String photographerAvatar;
    private String serviceId;
    private String serviceTitle;
    private String categoryId;
    private String packageId;
    private String packageName;
    private String tier;
    private String date;
    private String startTime;
    private String endTime;
    private int durationMinutes;
    private BookingLocation location;
    private List<AddOnLine> addOns = new ArrayList<>();
    private String notes = "";
    private PriceBreakdown pricing;
    private String status = "pending";
    private String paymentStatus = "unpaid";
    private String deliveryStatus = "not_started";
    private Instant createdAt;
    private Instant updatedAt;
    private List<BookingEvent> history = new ArrayList<>();
    private boolean reviewed;
    private RescheduleRequest rescheduleRequest;
    private Cancellation cancellation;
    private Dispute dispute;
}
