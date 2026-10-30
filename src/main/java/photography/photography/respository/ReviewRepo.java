package photography.photography.respository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.Review;

public interface ReviewRepo extends MongoRepository<Review, String> {
    List<Review> findByPhotographerIdOrderByCreatedAtDesc(String photographerId);

    Optional<Review> findByBookingId(String bookingId);

    List<Review> findByRatingGreaterThanEqualOrderByCreatedAtDesc(int rating, Pageable page);
}
