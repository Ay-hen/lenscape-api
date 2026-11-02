package photography.photography.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProfileReminderRepo extends MongoRepository<ProfileReminderState, String> {
}