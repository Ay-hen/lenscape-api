package photography.photography.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** E-mail verification endpoints (public: the person has no account or token yet). */
@RestController
@RequestMapping("/auth/otp")
public class OtpController {
    private final AuthenticationService service;

    public OtpController(AuthenticationService service) {
        this.service = service;
    }

    /** Confirms the code, creates the account and signs the user in (same answer as login). */
    @PostMapping("/verify")
    public AuthenticationResponse verify(@Valid @RequestBody VerifyOtpRequest request) {
        return service.verifyEmail(request.email(), request.otp());
    }

    /** Sends a new code. Always answers the same text, whether or not a registration is pending for that address. */
    @PostMapping("/resend")
    public Map<String, String> resend(@Valid @RequestBody ResendOtpRequest request) {
        service.resendCode(request.email());
        return Map.of("message", "If a pending registration exists for this email, a new code has been sent.");
    }
}
