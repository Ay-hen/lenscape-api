package photography.photography.respository;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.AvailabilityRules;

public interface AvailabilityRepo extends MongoRepository<AvailabilityRules, String> {
}
