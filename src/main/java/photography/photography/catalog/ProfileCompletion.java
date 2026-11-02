package photography.photography.catalog;

import java.util.List;

import photography.photography.model.PhotographerProfile;
import photography.photography.model.Values.PortfolioImage;

/** The single definition of "how complete is a photographer's profile". Weights add up to 100. */
public final class ProfileCompletion {
    private ProfileCompletion() {
    }

    public record Item(String key, String label, int weight, boolean done, String link) {
    }

    public record Result(int percent, List<Item> items) {
    }

    public static Result of(PhotographerProfile p, boolean publishedService, boolean publishedPackage, int minPhotos) {
        long photos = p.getPortfolio() == null ? 0 : p.getPortfolio().stream().filter(PortfolioImage::visible).count();
        List<Item> items = List.of(
                new Item("basics", "Name and a one-line headline", 10, filled(p.getDisplayName()) && filled(p.getHeadline()), "/studio/profile"),
                new Item("bio", "A bio of at least 100 characters", 15, p.getBio() != null && p.getBio().trim().length() >= 100, "/studio/profile"),
                new Item("avatar", "A profile picture", 10, filled(p.getAvatarUrl()), "/studio/profile"),
                new Item("location", "Where you work (city, or your shop on the map)", 10, p.getOnboardingStep() >= 2 || "shop".equals(p.getWorkMode()), "/studio/profile"),
                new Item("specialties", "At least one specialty", 10, p.getSpecialties() != null && !p.getSpecialties().isEmpty(), "/studio/profile"),
                new Item("portfolio", "At least " + minPhotos + " portfolio photos", 20, photos >= minPhotos, "/studio/portfolio"),
                new Item("service", "A published service", 10, publishedService, "/studio/services"),
                new Item("package", "A published package", 10, publishedPackage, "/studio/packages"),
                new Item("phone", "A contact phone number", 5, filled(p.getPhone()), "/studio/profile"));
        return new Result(items.stream().filter(Item::done).mapToInt(Item::weight).sum(), items);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}