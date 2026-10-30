package photography.photography.model;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Document("packages")
public class ServicePackage {
    @Id
    private String id;
    @Indexed
    private String serviceId;
    @Indexed
    private String photographerId;
    private String name = "New package";
    private String tier = "bronze";
    private Double price;
    private String currency = "MAD";
    private String description = "";
    private Integer durationMinutes = 60;
    private Integer editedPhotos = 10;
    private Integer locations = 1;
    private Integer outfitChanges = 0;
    private Integer deliveryDays = 14;
    private List<String> includes = new ArrayList<>();
    private List<String> extras = new ArrayList<>();
    private String travelConditions = "";
    private String bookingConditions = "";
    private boolean featured;
    private String status = "published";
}
