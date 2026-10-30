package photography.photography.booking;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.account.AccountService;
import photography.photography.booking.BookingDtos.BookingQuery;
import photography.photography.booking.BookingDtos.CancelBody;
import photography.photography.booking.BookingDtos.CreateBookingRequest;
import photography.photography.booking.BookingDtos.DeliveryBody;
import photography.photography.booking.BookingDtos.QuoteRequest;
import photography.photography.booking.BookingDtos.ReasonBody;
import photography.photography.booking.BookingDtos.RefundBody;
import photography.photography.booking.BookingDtos.RescheduleBody;
import photography.photography.booking.BookingDtos.ResolutionBody;
import photography.photography.booking.BookingDtos.RespondBody;
import photography.photography.booking.BookingDtos.ReviewBody;
import photography.photography.booking.BookingDtos.StatusBody;
import photography.photography.model.Booking;
import photography.photography.model.Review;
import photography.photography.model.Values.PageResult;
import photography.photography.model.Values.PriceBreakdown;

@RestController
@RequestMapping("/bookings")
public class BookingController {
    private final BookingService bookings;
    private final ReviewService reviews;
    private final AccountService accounts;

    public BookingController(BookingService bookings, ReviewService reviews, AccountService accounts) {
        this.bookings = bookings;
        this.reviews = reviews;
        this.accounts = accounts;
    }

    @PostMapping("/quote")
    public PriceBreakdown quote(@RequestBody QuoteRequest req) {
        return bookings.quote(req);
    }

    @PostMapping
    public Booking create(Principal me, @RequestBody CreateBookingRequest req) {
        return bookings.create(accounts.current(me.getName()), req);
    }

    @GetMapping
    public PageResult<Booking> list(Principal me, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String photographerId, @RequestParam(required = false) String customerId,
                                    @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return bookings.list(accounts.current(me.getName()), new BookingQuery(status, q, from, to, photographerId, customerId, page, pageSize));
    }

    @GetMapping("/{id}")
    public Booking get(Principal me, @PathVariable String id) {
        return bookings.get(id, accounts.current(me.getName()));
    }

    @PatchMapping("/{id}/status")
    public Booking status(Principal me, @PathVariable String id, @RequestBody StatusBody body) {
        return bookings.setStatus(id, body.status(), body.note(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/cancel")
    public Booking cancel(Principal me, @PathVariable String id, @RequestBody CancelBody body) {
        return bookings.cancel(id, body.reason(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/reschedule")
    public Booking reschedule(Principal me, @PathVariable String id, @RequestBody RescheduleBody body) {
        return bookings.requestReschedule(id, body.date(), body.startTime(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/reschedule/respond")
    public Booking respond(Principal me, @PathVariable String id, @RequestBody RespondBody body) {
        return bookings.respondReschedule(id, body.accept(), accounts.current(me.getName()));
    }

    @PatchMapping("/{id}/delivery")
    public Booking delivery(Principal me, @PathVariable String id, @RequestBody DeliveryBody body) {
        return bookings.setDelivery(id, body.status(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/dispute")
    public Booking dispute(Principal me, @PathVariable String id, @RequestBody ReasonBody body) {
        return bookings.openDispute(id, body.reason(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/dispute/resolve")
    public Booking resolve(Principal me, @PathVariable String id, @RequestBody ResolutionBody body) {
        return bookings.resolveDispute(id, body.resolution(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/refund")
    public Booking refund(Principal me, @PathVariable String id, @RequestBody RefundBody body) {
        return bookings.markRefund(id, body.kind(), accounts.current(me.getName()));
    }

    @PostMapping("/{id}/review")
    public Review review(Principal me, @PathVariable String id, @RequestBody ReviewBody body) {
        return reviews.submit(accounts.current(me.getName()), id, body.rating(), body.comment());
    }
}
