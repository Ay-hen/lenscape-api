package photography.photography.catalog;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.catalog.CatalogDtos.PackageHighlight;
import photography.photography.catalog.CatalogDtos.PhotographerSummary;
import photography.photography.catalog.CatalogDtos.SearchQuery;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.Review;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Values.CategoryDef;
import photography.photography.model.Values.FaqItem;
import photography.photography.model.Values.PageResult;
import photography.photography.model.Values.Slot;

/** Public, read-only marketplace endpoints (owners/admins get a little more where noted). */
@RestController
public class CatalogController {
    private final CatalogService catalog;
    private final AvailabilityService availability;

    public CatalogController(CatalogService catalog, AvailabilityService availability) {
        this.catalog = catalog;
        this.availability = availability;
    }

    @GetMapping("/categories")
    public List<CategoryDef> categories() {
        return catalog.categories();
    }

    @GetMapping("/photographers")
    public PageResult<PhotographerSummary> search(@RequestParam(required = false) String q, @RequestParam(required = false) String city,
                                                  @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                                                  @RequestParam(required = false) Double radiusKm, @RequestParam(required = false) String categoryId,
                                                  @RequestParam(required = false) Double minPrice, @RequestParam(required = false) Double maxPrice,
                                                  @RequestParam(required = false) String availableOn, @RequestParam(required = false) Double minRating,
                                                  @RequestParam(required = false) Integer minExperience, @RequestParam(required = false) String tier,
                                                  @RequestParam(required = false) Boolean verifiedOnly, @RequestParam(required = false) String sort,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return catalog.search(new SearchQuery(q, city, lat, lng, radiusKm, categoryId, minPrice, maxPrice, availableOn, minRating, minExperience, tier, verifiedOnly, sort, page, pageSize));
    }

    @GetMapping("/photographers/summaries")
    public List<PhotographerSummary> summaries(@RequestParam(defaultValue = "") String ids) {
        return catalog.summaries(Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    @GetMapping("/photographers/{id}")
    public PhotographerProfile photographer(@PathVariable String id, Principal me) {
        return catalog.photographer(id, me);
    }

    @GetMapping("/photographers/{id}/similar")
    public List<PhotographerSummary> similar(@PathVariable String id) {
        return catalog.similar(id);
    }

    @GetMapping("/photographers/{id}/services")
    public List<ServiceOffer> services(@PathVariable String id, @RequestParam(defaultValue = "false") boolean includeUnpublished, Principal me) {
        return catalog.services(id, includeUnpublished, me);
    }

    @GetMapping("/photographers/{id}/packages")
    public List<ServicePackage> packages(@PathVariable String id, @RequestParam(defaultValue = "false") boolean includeUnpublished, Principal me) {
        return catalog.packages(id, includeUnpublished, me);
    }

    @GetMapping("/photographers/{id}/reviews")
    public List<Review> reviews(@PathVariable String id) {
        return catalog.reviews(id);
    }

    @GetMapping("/reviews/featured")
    public List<Review> featuredReviews(@RequestParam(defaultValue = "3") int limit) {
        return catalog.featuredReviews(limit);
    }

    @PostMapping("/photographers/{id}/view")
    public ResponseEntity<Void> view(@PathVariable String id) {
        catalog.recordView(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/photographers/{id}/availability")
    public AvailabilityRules rules(@PathVariable String id) {
        return availability.rules(id);
    }

    @GetMapping("/photographers/{id}/slots")
    public List<Slot> slots(@PathVariable String id, @RequestParam String date, @RequestParam int duration) {
        return availability.slots(id, date, duration);
    }

    @GetMapping("/photographers/{id}/open-days")
    public List<String> openDays(@PathVariable String id, @RequestParam String dates, @RequestParam int duration) {
        return availability.openDays(id, Arrays.stream(dates.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(), duration);
    }

    @GetMapping("/packages/best-value")
    public List<PackageHighlight> bestValue(@RequestParam(defaultValue = "6") int limit) {
        return catalog.bestValue(limit);
    }

    @GetMapping("/content/banner")
    public Map<String, String> banner() {
        return Map.of("banner", catalog.banner());
    }

    @GetMapping("/content/faq")
    public List<FaqItem> faq() {
        return catalog.faqs();
    }
}
