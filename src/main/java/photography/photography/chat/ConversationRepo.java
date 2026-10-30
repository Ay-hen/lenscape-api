package photography.photography.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConversationRepo extends MongoRepository<Conversation, String> {
    Optional<Conversation> findByClientUsernameAndPhotographerUsername(String clientUsername, String photographerUsername);

    List<Conversation> findByClientUsernameOrPhotographerUsernameOrderByUpdatedAtDesc(String clientUsername, String photographerUsername);
}
