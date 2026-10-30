package photography.photography.controller;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.account.AccountService;
import photography.photography.account.UserDto;
import photography.photography.service.UserService;

/**
 * Legacy API of the original project. Admin-only routes are restricted in SecurityConfiguration; the rest check ownership in UserService.
 * New code should use /bookings, /photographers and /admin instead.
 */
@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;
    private final AccountService accounts;

    public UserController(UserService userService, AccountService accounts) {
        this.userService = userService;
        this.accounts = accounts;
    }

    @GetMapping("/get-user/{username}")
    public UserDto getUser(@PathVariable String username, Principal me) {
        return userService.getUser(username, accounts.current(me.getName()));
    }

    @GetMapping("/photographers/{city}")
    public ResponseEntity<?> getPhotographers(@PathVariable String city) {
        return userService.getPhotographerByCity(city);
    }

    @GetMapping("/city/{city}")
    public ResponseEntity<?> getUserByCity(@PathVariable String city) {
        return userService.findUser(city);
    }

    @GetMapping("/reservations/{username}")
    public ResponseEntity<?> getReservations(@PathVariable String username, Principal me) {
        return userService.getReservations(username, accounts.current(me.getName()));
    }

    @GetMapping("/reservations/all")
    public ResponseEntity<?> getAllReservations() {
        return userService.getAllReservations();
    }

    @GetMapping("/clients/all")
    public ResponseEntity<?> getAllClients() {
        return userService.listByRole("client");
    }

    @GetMapping("/photographers/all")
    public ResponseEntity<?> getAllPhotographers() {
        return userService.listByRole("photographer");
    }

    @GetMapping("/suspended/all")
    public ResponseEntity<?> getAllSuspended() {
        return userService.getAllSuspended();
    }

    @GetMapping("/suspension/{username}")
    public ResponseEntity<?> suspendUser(@PathVariable String username) {
        return userService.setSuspended(username, true);
    }

    @GetMapping("/unsuspension/{username}")
    public ResponseEntity<?> unsuspendUser(@PathVariable String username) {
        return userService.setSuspended(username, false);
    }

    /** The reserving customer is always the authenticated user; a "username" parameter, if sent, is ignored. */
    @PostMapping("/create/reservation")
    public ResponseEntity<?> createReservation(Principal me, @RequestParam("phone") String phone, @RequestParam("photographer") String photographer,
                                               @RequestParam("date") String date, @RequestParam("name") String name, @RequestParam("type") String type,
                                               @RequestParam("city") String city) {
        return userService.createReservation(accounts.current(me.getName()), phone, photographer, date, name, type, city);
    }

    @PostMapping("/edit/photographer")
    public ResponseEntity<?> editPhotographer(Principal me, @RequestParam("city") String city, @RequestParam("phone") String phone, @RequestParam("name") String name,
                                              @RequestParam("availability") String availability, @RequestParam("price") String price) {
        return userService.editPhotographer(accounts.current(me.getName()), name, city, phone, availability, price);
    }

    @PostMapping("/status")
    public ResponseEntity<?> updateStatus(Principal me, @RequestParam("id") String id, @RequestParam("status") String status) {
        return userService.setStatus(id, status, accounts.current(me.getName()));
    }

    @DeleteMapping("/delete/reservation/{id}")
    public ResponseEntity<Void> deleteReservation(Principal me, @PathVariable String id) {
        userService.deleteReservation(id, accounts.current(me.getName()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/delete/user/{username}")
    public ResponseEntity<Void> deleteUser(@PathVariable String username) {
        userService.deleteUser(username);
        return ResponseEntity.noContent().build();
    }
}
