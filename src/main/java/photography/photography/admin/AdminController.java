package photography.photography.admin;

import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.account.AccountService;
import photography.photography.account.UserDto;
import photography.photography.admin.AdminService.Overview;
import photography.photography.admin.AnalyticsService.PhotographerBundle;
import photography.photography.admin.AnalyticsService.PlatformBundle;
import photography.photography.model.Misc.Application;
import photography.photography.model.Misc.AuditEntry;
import photography.photography.model.Misc.ContentReport;
import photography.photography.model.Misc.PlatformSettings;
import photography.photography.model.ServiceOffer;
import photography.photography.model.ServicePackage;
import photography.photography.model.Values.PageResult;

/** Everything under /admin/** requires the "admin" authority (SecurityConfiguration). */
@RestController
public class AdminController {
    private final AdminService admin;
    private final AnalyticsService analytics;
    private final AccountService accounts;

    public AdminController(AdminService admin, AnalyticsService analytics, AccountService accounts) {
        this.admin = admin;
        this.analytics = analytics;
        this.accounts = accounts;
    }

    public record StatusChange(String status, String reason) {
    }

    public record Decision(String action, String note) {
    }

    public record Moderation(String state, Boolean featured) {
    }

    public record ReportState(String state) {
    }

    public record NewReport(String targetType, String targetId, String targetLabel, String reason) {
    }

    @GetMapping("/admin/overview")
    public Overview overview() {
        return admin.overview();
    }

    @GetMapping("/admin/users")
    public PageResult<UserDto> users(@RequestParam(required = false) String role, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String sort, @RequestParam(required = false) String dir, @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer pageSize) {
        return admin.users(role, status, q, sort, dir, page, pageSize);
    }

    @PatchMapping("/admin/users/{id}/status")
    public UserDto userStatus(Principal me, @PathVariable String id, @RequestBody StatusChange body) {
        return admin.setUserStatus(id, body.status(), body.reason(), accounts.current(me.getName()));
    }

    @GetMapping("/admin/applications")
    public List<Application> applications() {
        return admin.applications();
    }

    @PostMapping("/admin/applications/{pid}/decision")
    public Application decide(Principal me, @PathVariable String pid, @RequestBody Decision body) {
        return admin.decide(pid, body.action(), body.note(), accounts.current(me.getName()));
    }

    @GetMapping("/admin/services")
    public List<ServiceOffer> services() {
        return admin.allServices();
    }

    @GetMapping("/admin/packages")
    public List<ServicePackage> packages() {
        return admin.allPackages();
    }

    @PatchMapping("/admin/services/{id}/moderation")
    public ServiceOffer moderate(Principal me, @PathVariable String id, @RequestBody Moderation body) {
        return admin.moderateService(id, body.state(), body.featured(), accounts.current(me.getName()));
    }

    @GetMapping("/admin/reports")
    public List<ContentReport> reports() {
        return admin.reports();
    }

    @PatchMapping("/admin/reports/{id}")
    public ContentReport resolve(Principal me, @PathVariable String id, @RequestBody ReportState body) {
        return admin.resolveReport(id, body.state(), accounts.current(me.getName()));
    }

    @GetMapping("/admin/audit")
    public List<AuditEntry> audit() {
        return admin.audit();
    }

    @GetMapping("/admin/settings")
    public PlatformSettings settings() {
        return admin.settings();
    }

    @PutMapping("/admin/settings")
    public PlatformSettings saveSettings(Principal me, @RequestBody PlatformSettings body) {
        return admin.saveSettings(body, accounts.current(me.getName()));
    }

    /** Any signed-in user can report content. */
    @PostMapping("/reports")
    public ResponseEntity<Void> report(Principal me, @RequestBody NewReport body) {
        admin.report(body.targetType(), body.targetId(), body.targetLabel(), body.reason(), accounts.current(me.getName()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analytics/platform")
    public PlatformBundle platform(@RequestParam String from, @RequestParam String to) {
        return analytics.platform(from, to);
    }

    @GetMapping("/analytics/photographers/{id}")
    public PhotographerBundle photographer(Principal me, @PathVariable String id, @RequestParam String from, @RequestParam String to) {
        return analytics.photographer(id, from, to, accounts.current(me.getName()));
    }
}
