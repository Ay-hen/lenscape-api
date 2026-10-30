package photography.photography.catalog;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.model.Misc.FavoriteList;
import photography.photography.model.User;
import photography.photography.respository.MiscRepos.FavoriteRepo;
import photography.photography.respository.PhotographerProfileRepo;

@Service
public class FavoriteService {
    private final FavoriteRepo repo;
    private final PhotographerProfileRepo profiles;

    public FavoriteService(FavoriteRepo repo, PhotographerProfileRepo profiles) {
        this.repo = repo;
        this.profiles = profiles;
    }

    public List<String> list(User me) {
        return repo.findById(me.getUsername()).map(FavoriteList::getIds).orElse(List.of());
    }

    public List<String> toggle(User me, String photographerId) {
        if (!"client".equals(me.getRole())) {
            throw ApiException.forbidden("Sign in as a customer to save favourites.");
        }
        FavoriteList l = repo.findById(me.getUsername()).orElseGet(() -> {
            FavoriteList n = new FavoriteList();
            n.setUsername(me.getUsername());
            return n;
        });
        List<String> ids = new ArrayList<>(l.getIds());
        boolean has = ids.remove(photographerId);
        if (!has) {
            if (ids.size() >= 500) {
                throw ApiException.conflict("You have reached the favourites limit.");
            }
            if (!profiles.findById(photographerId).map(p -> "approved".equals(p.getApproval())).orElse(false)) {
                throw ApiException.notFound("Photographer not found");
            }
            ids.add(photographerId);
        }
        l.setIds(ids);
        repo.save(l);
        profiles.addFavorites(photographerId, has ? -1 : 1);
        return ids;
    }
}
