package photography.photography.respository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.Misc.Application;
import photography.photography.model.Misc.AuditEntry;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.Misc.ContentReport;
import photography.photography.model.Misc.CustomerExtras;
import photography.photography.model.Misc.FavoriteList;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.Misc.PromoCodeList;
import photography.photography.model.Misc.ViewStat;

/** One tiny repository per small collection, grouped in one file. */
public final class MiscRepos {
    private MiscRepos() {
    }

    public interface FavoriteRepo extends MongoRepository<FavoriteList, String> {
    }

    public interface PromoRepo extends MongoRepository<PromoCodeList, String> {
    }

    public interface CustomerExtrasRepo extends MongoRepository<CustomerExtras, String> {
    }

    public interface ApplicationRepo extends MongoRepository<Application, String> {
        List<Application> findAllByOrderBySubmittedAtDesc();
    }

    public interface AuditRepo extends MongoRepository<AuditEntry, String> {
        List<AuditEntry> findAllByOrderByAtDesc(Pageable page);

        List<AuditEntry> findAllByOrderByAtDesc();
    }

    public interface ReportRepo extends MongoRepository<ContentReport, String> {
        List<ContentReport> findAllByOrderByAtDesc();

        long countByState(String state);
    }

    public interface SettingsRepo extends MongoRepository<PlatformSettings, String> {
    }

    public interface AutomationRepo extends MongoRepository<AutomationSettings, String> {
    }

    public interface ViewStatRepo extends MongoRepository<ViewStat, String> {
        List<ViewStat> findByPhotographerIdAndDayBetween(String photographerId, String from, String to);
    }

    /** Kept to make the optional-lookup style explicit for callers. */
    public static <T> T orNull(Optional<T> o) {
        return o.orElse(null);
    }
}
