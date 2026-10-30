package photography.photography.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;
import photography.photography.model.Values.Album;
import photography.photography.model.Values.CancellationPolicy;
import photography.photography.model.Values.GearItem;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.PortfolioImage;

/** Public marketplace profile of a photographer. The id equals the owner's username (the User primary key). */
@Data
@NoArgsConstructor
@Document("photographer_profiles")
public class PhotographerProfile {
    @Id
    private String id;
    private String userId;
    private String displayName;
    private String businessName;
    private String headline = "";
    private String bio = "";
    private String avatarUrl = "";
    private String coverUrl = "";
    @Indexed
    private String city;
    private String neighborhood;
    private String workMode = "freelancer";
    private String shopAddress;
    private GeoPoint publicLocation;
    private int serviceRadiusKm = 25;
    private List<String> languages = new ArrayList<>();
    private List<GearItem> gear = new ArrayList<>();
    private boolean showGear = true;
    private List<String> specialties = new ArrayList<>();
    private int yearsExperience;
    private double rating;
    private int reviewCount;
    private int completedBookings;
    private int responseTimeHours = 4;
    @Indexed
    /** Photographers go live as soon as they sign up: there is no review step. Kept as a field for older data and the optional identity badge. */
    private String approval = "approved";
    private boolean identityVerified;
    private CancellationPolicy cancellationPolicy = CancellationPolicy.standard();
    private List<String> policies = new ArrayList<>();
    private int favoritesCount;
    private int profileViews;
    private Instant joinedAt;
    private boolean featured;
    private String phone;
    private List<PortfolioImage> portfolio = new ArrayList<>();
    private List<Album> albums = new ArrayList<>();
    private int onboardingStep = 1;
}
