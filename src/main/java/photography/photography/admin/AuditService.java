package photography.photography.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import photography.photography.model.Misc.AuditEntry;
import photography.photography.respository.MiscRepos.AuditRepo;

@Service
public class AuditService {
    private final AuditRepo repo;

    public AuditService(AuditRepo repo) {
        this.repo = repo;
    }

    public void log(String actor, String action, String target, String detail) {
        AuditEntry e = new AuditEntry();
        e.setId(UUID.randomUUID().toString());
        e.setAt(Instant.now());
        e.setActor(actor);
        e.setAction(action);
        e.setTarget(target);
        e.setDetail(detail == null || detail.isBlank() ? null : detail);
        repo.insert(e);
    }

    public List<AuditEntry> recent(int limit) {
        return repo.findAllByOrderByAtDesc(PageRequest.of(0, limit));
    }
}
