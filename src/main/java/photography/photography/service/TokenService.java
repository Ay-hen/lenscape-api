package photography.photography.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import photography.photography.respository.TokenRepo;



@Service
public class TokenService {
    @Autowired
    private TokenRepo repo;

    public void revokeToken(String token) {
        var tokenObj = repo.findById(token).orElseThrow();
        tokenObj.setRevoked(true);
        repo.save(tokenObj);
    }

    public boolean isRevoked(String token) {
        var tokenObj = repo.findById(token).orElseThrow();
        return tokenObj.isRevoked();
    }

    public void expireToken(String token) {
        var tokenObj = repo.findById(token).orElseThrow();
        tokenObj.setExpired(true);
        repo.save(tokenObj);
    }

    public boolean isExpired(String token) {
        // A token that is not in the store (logged out / unknown) is treated as expired instead of throwing.
        return repo.findById(token).map(t -> t.isExpired() || t.isRevoked()).orElse(true);
    }

    /** True only for a token that is stored, not expired and not revoked. */
    public boolean isActive(String token) {
        return !isExpired(token);
    }
}