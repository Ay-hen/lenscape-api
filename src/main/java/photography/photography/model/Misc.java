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
import photography.photography.model.Values.ApprovalEvent;
import photography.photography.model.Values.CategoryDef;
import photography.photography.model.Values.FaqItem;
import photography.photography.model.Values.MessageTemplate;
import photography.photography.model.Values.NamedTemplate;
import photography.photography.model.Values.NotificationPrefs;
import photography.photography.model.Values.PromoCode;
import photography.photography.model.Values.SavedLocation;

/** Small documents that do not deserve their own file. Each is its own Mongo collection. */
public final class Misc {
    private Misc() {
    }

    @Data
    @NoArgsConstructor
    @Document("favorites")
    public static class FavoriteList {
        @Id
        private String username;
        private List<String> ids = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @Document("promo_codes")
    public static class PromoCodeList {
        @Id
        private String photographerId;
        private List<PromoCode> codes = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @Document("customer_profiles")
    public static class CustomerExtras {
        @Id
        private String username;
        private List<SavedLocation> savedLocations = new ArrayList<>();
        private NotificationPrefs notificationPrefs = new NotificationPrefs(true, true, false, false);
    }

    @Data
    @NoArgsConstructor
    @Document("applications")
    public static class Application {
        @Id
        private String photographerId;
        private String displayName;
        private String email;
        private String city;
        private String avatarUrl;
        private String status = "pending";
        private boolean identityVerified;
        private Instant submittedAt;
        private List<String> portfolioSamples = new ArrayList<>();
        private List<String> specialties = new ArrayList<>();
        private List<ApprovalEvent> history = new ArrayList<>();
        private String internalNote;
    }

    @Data
    @NoArgsConstructor
    @Document("audit")
    public static class AuditEntry {
        @Id
        private String id;
        @Indexed
        private Instant at;
        private String actor;
        private String action;
        private String target;
        private String detail;
    }

    @Data
    @NoArgsConstructor
    @Document("reports")
    public static class ContentReport {
        @Id
        private String id;
        private String targetType;
        private String targetId;
        private String targetLabel;
        private String reason;
        private String reportedBy;
        private Instant at;
        private String state = "open";
    }

    /** Singleton document with id "platform". */
    @Data
    @NoArgsConstructor
    @Document("settings")
    public static class PlatformSettings {
        public static final String ID = "platform";
        @Id
        private String id = ID;
        private String brandName = "Lenscape";
        private String tagline = "";
        private String currency = "MAD";
        private String locale = "en";
        private double commissionPercent = 10;
        private int defaultFreeCancelHours = 72;
        private int defaultMinNoticeHours = 24;
        private List<CategoryDef> categories = new ArrayList<>();
        private List<FaqItem> faqs = new ArrayList<>();
        private List<NamedTemplate> notificationTemplates = new ArrayList<>();
        private List<String> featuredPhotographerIds = new ArrayList<>();
        private String maintenanceBanner = "";
    }

    @Data
    @NoArgsConstructor
    @Document("view_stats")
    @CompoundIndex(name = "pid_day", def = "{'photographerId': 1, 'day': 1}", unique = true)
    public static class ViewStat {
        @Id
        private String id;
        private String photographerId;
        private String day;
        private long count;
    }

    @Data
    @NoArgsConstructor
    @Document("counters")
    public static class Counter {
        @Id
        private String id;
        private long value;
    }

    /** Per-photographer automation settings (the Angular {@code AutomationSettings}). */
    @Data
    @NoArgsConstructor
    @Document("automation")
    public static class AutomationSettings {
        @Id
        private String photographerId;
        private BookingRequestNotify bookingRequestNotify = new BookingRequestNotify(true, true, true);
        private ConfirmationMessage confirmationMessage = new ConfirmationMessage(true, "t1");
        private Reminders reminders = new Reminders(true, new ArrayList<>(List.of(48, 24)), "t2");
        private FollowUp followUp = new FollowUp(false, 48, "t3");
        private ReviewRequest reviewRequest = new ReviewRequest(true, 72);
        private AutoExpire autoExpire = new AutoExpire(true, 72);
        private PendingNudge pendingNudge = new PendingNudge(true, 24);
        private AutoAccept autoAccept = new AutoAccept(false, 72, 3000.0, true);
        private RepeatCustomerOffer repeatCustomerOffer = new RepeatCustomerOffer(false, 10);
        private GalleryTracking galleryTracking = new GalleryTracking(true);
        private List<MessageTemplate> templates = new ArrayList<>();
        private Instant updatedAt;

        public record BookingRequestNotify(boolean enabled, boolean email, boolean inApp) {
        }

        public record ConfirmationMessage(boolean enabled, String templateId) {
        }

        public record Reminders(boolean enabled, List<Integer> hoursBefore, String templateId) {
            public Reminders {
                hoursBefore = hoursBefore == null ? new ArrayList<>() : hoursBefore;
            }
        }

        public record FollowUp(boolean enabled, int delayHours, String templateId) {
        }

        public record ReviewRequest(boolean enabled, int delayHours) {
        }

        public record AutoExpire(boolean enabled, int afterHours) {
        }

        public record PendingNudge(boolean enabled, int afterHours) {
        }

        public record AutoAccept(boolean enabled, int minNoticeHours, Double maxPriceMad, boolean repeatCustomersOnly) {
        }

        public record RepeatCustomerOffer(boolean enabled, int percent) {
        }

        public record GalleryTracking(boolean enabled) {
        }
    }
}
