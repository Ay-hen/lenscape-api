package photography.photography.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document("reviews")
public class Review {
    @Id
    private String id;
    @Indexed(unique = true)
    private String bookingId;
    @Indexed
    private String photographerId;
    private String customerId;
    private String customerName;
    private String photographerName;
    private String serviceTitle;
    private int rating;
    private String comment;
    private Instant createdAt;
    private String reply;
}
