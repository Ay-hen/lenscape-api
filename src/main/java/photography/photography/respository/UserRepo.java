package photography.photography.respository;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.User;
import java.util.List;
import java.util.Optional;


public interface UserRepo extends MongoRepository<User, String>{
    public List<User> findByCity(String city);
    public Optional <User> findByUsername(String username);

    List<User> findByRole(String role);

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    long countByRole(String role);

    List<User> findByCityAndRole(String city, String role);
    List<User> findBySuspended(boolean suspended);
}
