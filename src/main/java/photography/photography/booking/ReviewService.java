package photography.photography.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import photography.photography.api.ApiException;
import photography.photography.api.Check;
import photography.photography.model.Booking;
import photography.photography.model.PhotographerProfile;
import photography.photography.model.Review;
import photography.photography.model.User;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.BookingRepo;
import photography.photography.respository.PhotographerProfileRepo;
import photography.photography.respository.ReviewRepo;

@Service
public class ReviewService {
    private final ReviewRepo reviews;
    private final BookingRepo bookings;
    private final PhotographerProfileRepo profiles;
    private final Notifier notifier;

    public ReviewService(ReviewRepo reviews, BookingRepo bookings, PhotographerProfileRepo profiles, Notifier notifier) {
        this.reviews = reviews;
        this.bookings = bookings;
        this.profiles = profiles;
        this.notifier = notifier;
    }

    /** Only the customer of a COMPLETED booking, once. The rating shown on the profile is recomputed from all reviews. */
    public Review submit(User me, String bookingId, int rating, String comment) {
        Booking b = bookings.findById(bookingId).filter(x -> x.getCustomerId().equals(me.getUsername())).orElseThrow(() -> ApiException.forbidden("Not your booking"));
        if (!"completed".equals(b.getStatus())) {
            throw ApiException.conflict("You can review a booking only after it is completed.");
        }
        if (b.isReviewed() || reviews.findByBookingId(bookingId).isPresent()) {
            throw ApiException.conflict("You already reviewed this booking.");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.validation("Rating must be 1–5.", "rating", "1-5");
        }
        String text = comment == null ? "" : comment.trim();
        if (text.length() < 10 || text.length() > 2000) {
            throw ApiException.validation("Please write between 10 and 2000 characters.", "comment", text.length() < 10 ? "Too short" : "Too long");
        }
        Review r = new Review();
        r.setId(UUID.randomUUID().toString());
        r.setBookingId(bookingId);
        r.setPhotographerId(b.getPhotographerId());
        r.setCustomerId(me.getUsername());
        r.setCustomerName(me.getName());
        r.setPhotographerName(b.getPhotographerName());
        r.setServiceTitle(b.getServiceTitle());
        r.setRating(rating);
        r.setComment(text);
        r.setCreatedAt(Instant.now());
        try {
            reviews.insert(r);
        } catch (DuplicateKeyException race) {
            throw ApiException.conflict("You already reviewed this booking.");
        }
        b.setReviewed(true);
        bookings.save(b);
        recompute(b.getPhotographerId());
        notifier.notify(b.getPhotographerId(), NotificationType.REVIEW, "New review", me.getName() + " left a " + rating + "-star review.", "/studio", "review:" + b.getPhotographerId());
        return r;
    }

    public Review reply(User me, String reviewId, String text) {
        Review r = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review not found"));
        if (!r.getPhotographerId().equals(me.getUsername())) {
            throw ApiException.forbidden("Not your review");
        }
        r.setReply(Check.text(text, "reply", 1, 1000));
        return reviews.save(r);
    }

    private void recompute(String photographerId) {
        List<Review> all = reviews.findByPhotographerIdOrderByCreatedAtDesc(photographerId);
        PhotographerProfile p = profiles.findById(photographerId).orElse(null);
        if (p == null) {
            return;
        }
        double avg = all.stream().mapToInt(Review::getRating).average().orElse(0);
        p.setRating(Math.round(avg * 10) / 10.0);
        p.setReviewCount(all.size());
        profiles.save(p);
    }
}
