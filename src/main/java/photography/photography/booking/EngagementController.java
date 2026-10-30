package photography.photography.booking;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.account.AccountService;
import photography.photography.booking.BookingDtos.ReplyBody;
import photography.photography.catalog.FavoriteService;
import photography.photography.model.Review;

/** Favourites and review replies. */
@RestController
public class EngagementController {
    private final FavoriteService favorites;
    private final ReviewService reviews;
    private final AccountService accounts;

    public EngagementController(FavoriteService favorites, ReviewService reviews, AccountService accounts) {
        this.favorites = favorites;
        this.reviews = reviews;
        this.accounts = accounts;
    }

    @GetMapping("/favorites")
    public List<String> list(Principal me) {
        return favorites.list(accounts.current(me.getName()));
    }

    @PostMapping("/favorites/{photographerId}/toggle")
    public List<String> toggle(Principal me, @PathVariable String photographerId) {
        return favorites.toggle(accounts.current(me.getName()), photographerId);
    }

    @PostMapping("/reviews/{id}/reply")
    public Review reply(Principal me, @PathVariable String id, @RequestBody ReplyBody body) {
        return reviews.reply(accounts.current(me.getName()), id, body.text());
    }
}
