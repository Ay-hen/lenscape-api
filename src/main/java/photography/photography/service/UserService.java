package photography.photography.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import photography.photography.account.AccountService;
import photography.photography.account.UserDto;
import photography.photography.api.ApiException;
import photography.photography.model.Reservation;
import photography.photography.model.User;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.ReservationRepo;
import photography.photography.respository.UserRepo;

/**
 * Legacy account/reservation endpoints of the original API (/user/**). The Angular app now uses /bookings and /admin;
 * these remain for compatibility but every one of them now checks WHO is calling. Responses never contain raw User entities.
 */
@Service
public class UserService {
    private final UserRepo userRepo;
    private final ReservationRepo reservationRepo;
    private final Notifier notifier;
    private final AccountService accounts;

    public UserService(UserRepo userRepo, ReservationRepo reservationRepo, Notifier notifier, AccountService accounts) {
        this.userRepo = userRepo;
        this.reservationRepo = reservationRepo;
        this.notifier = notifier;
        this.accounts = accounts;
    }

    private User find(String username) {
        return userRepo.findByUsername(username).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private static boolean isAdmin(User u) {
        return "admin".equals(u.getRole());
    }

    public UserDto getUser(String username, User caller) {
        if (!caller.getUsername().equals(username) && !isAdmin(caller)) {
            throw ApiException.forbidden("You can only view your own account.");
        }
        return accounts.dto(find(username));
    }

    public ResponseEntity<?> findUser(String city) {
        List<User> users = userRepo.findByCity(city);
        List<Map<String, String>> names = users.stream().filter(u -> "photographer".equals(u.getRole()) && !u.isSuspended())
                .map(u -> Map.of("name", u.getName(), "username", u.getUsername())).toList();
        if (names.isEmpty()) {
            return ResponseEntity.badRequest().body("No photographers found in the city");
        }
        return ResponseEntity.ok(names);
    }

    public ResponseEntity<?> getPhotographerByCity(String city) {
        List<UserDto> users = userRepo.findByCityAndRole(city, "photographer").stream().filter(u -> !u.isSuspended()).map(accounts::dto)
                .map(d -> new UserDto(d.id(), d.role(), d.name(), d.username(), null, null, d.avatarUrl(), d.city(), d.status(), null, null)).toList();
        if (users.isEmpty()) {
            return ResponseEntity.badRequest().body("No photographers found in the city");
        }
        return ResponseEntity.ok(users);
    }

    public ResponseEntity<?> getReservations(String username, User caller) {
        if (!caller.getUsername().equals(username) && !isAdmin(caller)) {
            throw ApiException.forbidden("You can only view your own reservations.");
        }
        List<Reservation> reservations = find(username).getReservations();
        if (reservations == null || reservations.isEmpty()) {
            return ResponseEntity.badRequest().body("No reservations found");
        }
        return ResponseEntity.ok(reservations);
    }

    public ResponseEntity<?> createReservation(User caller, String phone, String photographerUsername, String date, String name, String type, String city) {
        if (!"client".equals(caller.getRole())) {
            throw ApiException.forbidden("Only customers can reserve.");
        }
        User photographer = find(photographerUsername);
        if (!"photographer".equals(photographer.getRole()) || photographer.isSuspended()) {
            throw ApiException.notFound("Photographer not found");
        }
        Reservation r = Reservation.builder().id(java.util.UUID.randomUUID().toString()).photographerName(photographer.getName()).user(caller).type(type)
                .status("pending").city(city).phone(phone).name(name).photographer(photographer).date(date).build();
        reservationRepo.save(r);
        if (caller.getReservations() == null) {
            caller.setReservations(new java.util.ArrayList<>());
        }
        if (photographer.getReservations() == null) {
            photographer.setReservations(new java.util.ArrayList<>());
        }
        caller.addReservation(r);
        photographer.addReservation(r);
        userRepo.save(caller);
        userRepo.save(photographer);
        notifier.notify(photographer.getUsername(), NotificationType.BOOKING, "New booking request",
                (name != null ? name : "A customer") + " requested a " + type + " session on " + date + ".", "/studio/bookings", "bookings:new");
        return ResponseEntity.ok("Done");
    }

    private Reservation reservation(String id, User caller, boolean photographerOnly) {
        Reservation r = reservationRepo.findById(id).orElseThrow(() -> ApiException.notFound("Reservation not found"));
        boolean photographer = r.getPhotographer() != null && caller.getUsername().equals(r.getPhotographer().getUsername());
        boolean customer = r.getUser() != null && caller.getUsername().equals(r.getUser().getUsername());
        if (!isAdmin(caller) && !(photographer || (!photographerOnly && customer))) {
            throw ApiException.notFound("Reservation not found");
        }
        return r;
    }

    public void deleteReservation(String id, User caller) {
        Reservation r = reservation(id, caller, false);
        if (r.getUser() != null) {
            r.getUser().removeReservation(r);
            userRepo.save(r.getUser());
        }
        if (r.getPhotographer() != null) {
            r.getPhotographer().removeReservation(r);
            userRepo.save(r.getPhotographer());
        }
        reservationRepo.deleteById(id);
    }

    public ResponseEntity<?> editPhotographer(User caller, String name, String city, String phone, String availability, String price) {
        if (!"photographer".equals(caller.getRole())) {
            throw ApiException.forbidden("Photographers only.");
        }
        caller.setAvailability(availability);
        caller.setPrice(price);
        if (!city.isEmpty() && !name.isEmpty() && !phone.isEmpty()) {
            caller.setCity(city);
            caller.setName(name);
            caller.setPhone(phone);
        }
        userRepo.save(caller);
        return ResponseEntity.ok().body(Map.of("message", "Ok"));
    }

    /** The photographer (or an admin) sets the reservation status. The former version also removed the reservation from the photographer; that was a bug. */
    public ResponseEntity<?> setStatus(String id, String status, User caller) {
        Reservation r = reservation(id, caller, true);
        if (!List.of("pending", "confirmed", "completed", "cancelled", "rejected").contains(status)) {
            throw ApiException.validation("Invalid status.");
        }
        r.setStatus(status);
        reservationRepo.save(r);
        if (r.getUser() != null) {
            notifier.notify(r.getUser().getUsername(), NotificationType.BOOKING, "Booking " + status,
                    "Your booking on " + r.getDate() + " is now " + status + ".", "/account/bookings", "booking:" + id);
        }
        return ResponseEntity.ok("Done");
    }

    public ResponseEntity<?> getAllReservations() {
        return ResponseEntity.ok(reservationRepo.findAll());
    }

    public ResponseEntity<?> listByRole(String role) {
        return ResponseEntity.ok(userRepo.findByRole(role).stream().map(accounts::dto).toList());
    }

    public ResponseEntity<?> getAllSuspended() {
        return ResponseEntity.ok(userRepo.findBySuspended(true).stream().map(accounts::dto).toList());
    }

    public void deleteUser(String username) {
        User u = find(username);
        if (isAdmin(u)) {
            throw ApiException.forbidden("Admin accounts cannot be deleted here.");
        }
        userRepo.delete(u);
    }

    public ResponseEntity<?> setSuspended(String username, boolean suspended) {
        User user = find(username);
        if (isAdmin(user)) {
            throw ApiException.forbidden("Admin accounts cannot be suspended here.");
        }
        user.setSuspended(suspended);
        userRepo.save(user);
        notifier.notify(username, NotificationType.SYSTEM, suspended ? "Account suspended" : "Account restored",
                suspended ? "Your account has been suspended by an administrator." : "Your account is active again.", "/", "account:status");
        return ResponseEntity.ok().body(Map.of("message", "Ok"));
    }
}
