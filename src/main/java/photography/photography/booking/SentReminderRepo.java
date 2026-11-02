package photography.photography.booking;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface SentReminderRepo extends MongoRepository<SentReminder, String> {
}