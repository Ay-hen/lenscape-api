package photography.photography.catalog;

import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.booking.BookingDtos.StatusBody;
import photography.photography.catalog.CatalogDtos.LocationInput;
import photography.photography.catalog.CatalogDtos.PortfolioBody;
import photography.photography.catalog.CatalogDtos.ProfilePatch;
import photography.photography.model.AvailabilityRules;
import photography.photography.model.Misc.AutomationSettings;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.Values.PromoCode;

/** The photographer's own workspace. Access is limited to role "photographer" in SecurityConfiguration and always scoped to the caller. */
@RestController
@org.springframework.web.bind.annotation.RequestMapping("/photographers/me")
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final AvailabilityService availability;

    public WorkspaceController(WorkspaceService workspace, AvailabilityService availability) {
        this.workspace = workspace;
        this.availability = availability;
    }

    @GetMapping
    public PhotographerProfile profile(Principal me) {
        return workspace.mine(me.getName());
    }

    @PutMapping
    public PhotographerProfile update(Principal me, @RequestBody ProfilePatch patch) {
        return workspace.updateProfile(me.getName(), patch);
    }

    @PostMapping("/submit")
    public PhotographerProfile submit(Principal me) {
        return workspace.submit(me.getName());
    }

    @PutMapping("/location")
    public PhotographerProfile location(Principal me, @RequestBody LocationInput in) {
        return workspace.setLocation(me.getName(), in.workMode(), in.city(), in.radiusKm(), in.point(), in.shopAddress());
    }

    @PostMapping("/services")
    public ServiceOffer saveService(Principal me, @RequestBody ServiceOffer body) {
        return workspace.saveService(me.getName(), body);
    }

    @PatchMapping("/services/{id}/status")
    public ServiceOffer serviceStatus(Principal me, @PathVariable String id, @RequestBody StatusBody body) {
        return workspace.setServiceStatus(me.getName(), id, body.status());
    }

    @PostMapping("/packages")
    public ServicePackage savePackage(Principal me, @RequestBody ServicePackage body) {
        return workspace.savePackage(me.getName(), body);
    }

    @PatchMapping("/packages/{id}/status")
    public ServicePackage packageStatus(Principal me, @PathVariable String id, @RequestBody StatusBody body) {
        return workspace.setPackageStatus(me.getName(), id, body.status());
    }

    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Void> deletePackage(Principal me, @PathVariable String id) {
        workspace.deletePackage(me.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/portfolio")
    public ResponseEntity<Void> portfolio(Principal me, @RequestBody PortfolioBody body) {
        workspace.savePortfolio(me.getName(), body.images(), body.albums());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/promo-codes")
    public List<PromoCode> promos(Principal me) {
        return workspace.promoCodes(me.getName());
    }

    @PutMapping("/promo-codes")
    public List<PromoCode> savePromos(Principal me, @RequestBody List<PromoCode> codes) {
        return workspace.savePromoCodes(me.getName(), codes);
    }

    @GetMapping("/automation")
    public AutomationSettings automation(Principal me) {
        return workspace.automation(me.getName());
    }

    @PutMapping("/automation")
    public AutomationSettings saveAutomation(Principal me, @RequestBody AutomationSettings body) {
        return workspace.saveAutomation(me.getName(), body);
    }

    @PutMapping("/availability")
    public AvailabilityRules saveAvailability(Principal me, @RequestBody AvailabilityRules rules) {
        return availability.save(me.getName(), rules);
    }
}
