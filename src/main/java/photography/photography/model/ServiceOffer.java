package photography.photography.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;
import photography.photography.model.Values.AddOn;

/** A service a photographer sells (the Angular {@code Service}). */
@Data
@NoArgsConstructor
@Document("services")
public class ServiceOffer {
    @Id
    private String id;
    @Indexed
    private String photographerId;
    private String title;
    private String description = "";
    private String categoryId;
    private String coverImage = "";
    private List<String> gallery = new ArrayList<>();
    private String pricingModel = "fixed";
    private Double basePrice;
    private String currency = "MAD";
    private Integer durationMinutes = 120;
    private List<String> deliverables = new ArrayList<>();
    private Integer editedPhotos = 30;
    private Integer deliveryDays = 14;
    private String travelRules = "";
    private List<AddOn> addOns = new ArrayList<>();
    private String status = "published";
    private String moderation = "ok";
    private boolean featured;
    private Instant updatedAt;
}
