package photography.photography.verification;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PasswordResetRepo extends MongoRepository<PasswordReset, String> {
    Optional<PasswordReset> findByEmail(String email);
}