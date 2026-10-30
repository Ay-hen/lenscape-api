package photography.photography.catalog;

import java.util.List;

import photography.photography.model.ServicePackage;
import photography.photography.model.Values.CancellationPolicy;
import photography.photography.model.Values.GearItem;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.GeoPoint;
import photography.photography.model.Values.PriceInfo;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public record PhotographerSummary(String id, String displayName, String avatarUrl, String coverUrl, String city, String workMode, String headline,
                                      double rating, int reviewCount, List<String> specialties, int yearsExperience, PriceInfo startingPrice,
                                      List<String> tiers, GeoPoint location, Double distanceKm, Boolean availableOnDate, String nextAvailable,
                                      boolean identityVerified, boolean featured, int popularity, int serviceRadiusKm) {
    }

    public record PackageHighlight(ServicePackage pkg, String serviceTitle, String pricingModel, PhotographerSummary photographer) {
    }

    /** Query of GET /photographers; mirrors the Angular PhotographerQuery. */
    public record SearchQuery(String q, String city, Double lat, Double lng, Double radiusKm, String categoryId, Double minPrice, Double maxPrice,
                              String availableOn, Double minRating, Integer minExperience, String tier, Boolean verifiedOnly, String sort,
                              Integer page, Integer pageSize) {
    }

    /** Fields a photographer may change on their own profile. Anything else in the request body (approval, rating, location ...) is ignored. */
    public record ProfilePatch(String displayName, String businessName, String headline, String bio, String avatarUrl, String coverUrl, String neighborhood,
                               List<String> languages, List<GearItem> gear, Boolean showGear, List<String> specialties, Integer yearsExperience,
                               Integer responseTimeHours, CancellationPolicy cancellationPolicy, List<String> policies, String phone, Integer onboardingStep) {
    }

    /** Body of PUT /photographers/me/location. */
    public record LocationInput(String workMode, String city, Integer radiusKm, GeoPoint point, String shopAddress) {
    }

    public record PortfolioBody(List<photography.photography.model.Values.PortfolioImage> images, List<photography.photography.model.Values.Album> albums) {
    }
}
