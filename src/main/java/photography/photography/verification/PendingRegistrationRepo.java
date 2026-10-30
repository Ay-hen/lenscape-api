package photography.photography.verification;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PendingRegistrationRepo extends MongoRepository<PendingRegistration, String> {
    Optional<PendingRegistration> findByEmail(String email);

    boolean existsByEmail(String email);
}
