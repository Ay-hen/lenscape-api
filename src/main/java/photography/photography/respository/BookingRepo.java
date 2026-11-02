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

    /** Bookings of one status whose date (YYYY-MM-DD) lies in [fromDate, toDate]. */
    @org.springframework.data.mongodb.repository.Query("{ 'status': ?0, 'date': { $gte: ?1, $lte: ?2 } }")
    List<Booking> findByStatusAndDateRange(String status, String fromDate, String toDate);

    boolean existsByCustomerIdAndPhotographerId(String customerId, String photographerId);

    boolean existsByCustomerIdAndPhotographerIdAndStatus(String customerId, String photographerId, String status);
}