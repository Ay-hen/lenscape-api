package photography.photography.respository;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.Reservation;
import photography.photography.model.User;

public interface ReservationRepo extends MongoRepository<Reservation, String>{

    /** True when the customer and the photographer share at least one reservation (used to authorise photographer-initiated chats). */
    boolean existsByUserAndPhotographer(User user, User photographer);
}
