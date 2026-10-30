package photography.photography.respository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import photography.photography.model.Booking;

public interface BookingRepo extends MongoRepository<Booking, String> {
    List<Booking> findByCustomerId(String customerId);

    List<Booking> findByPhotographerId(String photographerId);

    /** Bookings that block a photographer's calendar. */
    List<Booking> findByPhotographerIdAndStatusIn(String photographerId, Collection<String> statuses);

    List<Booking> findByStatusIn(Collection<String> statuses);

    List<Booking> findByStatusInAndDateGreaterThanEqual(Collection<String> statuses, String date);

    boolean existsByCustomerIdAndPhotographerId(String customerId, String photographerId);

    boolean existsByCustomerIdAndPhotographerIdAndStatus(String customerId, String photographerId, String status);
}
