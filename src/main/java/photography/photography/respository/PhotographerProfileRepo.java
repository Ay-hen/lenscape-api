package photography.photography.respository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

import photography.photography.model.PhotographerProfile;

public interface PhotographerProfileRepo extends MongoRepository<PhotographerProfile, String> {
    List<PhotographerProfile> findByApproval(String approval);

    List<PhotographerProfile> findByApprovalIn(List<String> approvals);

    /** Atomic counters: concurrent visitors must never lose an increment. */
    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'profileViews': 1 } }")
    void incrementViews(String id);

    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'completedBookings': 1 } }")
    void incrementCompleted(String id);

    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'favoritesCount': ?1 } }")
    void addFavorites(String id, int delta);
}
