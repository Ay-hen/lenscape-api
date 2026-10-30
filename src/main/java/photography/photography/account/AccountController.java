package photography.photography.account;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import photography.photography.account.AccountService.CustomerPatch;
import photography.photography.account.AccountService.CustomerProfileDto;
import photography.photography.auth.ChangePasswordRequest;

@RestController
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** The signed-in account (role and status come from the database, never from the token). */
    @GetMapping("/user/me")
    public UserDto me(Principal me) {
        return accounts.me(me.getName());
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(Principal me, @RequestHeader(value = "Authorization", required = false) String authorization,
                                               @Valid @RequestBody ChangePasswordRequest body) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        accounts.changePassword(me.getName(), body.current(), body.next(), token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/customers/me")
    public CustomerProfileDto customer(Principal me) {
        return accounts.customer(me.getName());
    }

    @PutMapping("/customers/me")
    public CustomerProfileDto updateCustomer(Principal me, @RequestBody CustomerPatch patch) {
        return accounts.updateCustomer(me.getName(), patch);
    }
}
