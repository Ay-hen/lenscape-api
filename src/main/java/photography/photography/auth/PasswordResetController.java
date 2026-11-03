package photography.photography.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import photography.photography.verification.PasswordResetService;

/** Password recovery (public: the person cannot sign in). */
@RestController
@RequestMapping("/auth/password")
public class PasswordResetController {
    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** Always answers the same text, whether or not an account uses that address. */
    @PostMapping("/forgot")
    public Map<String, Object> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        service.request(request.getEmail());
        return Map.of("message", "If an account uses this email, we sent a 6-digit code to it.", "expiresInSeconds", service.ttlSeconds());
    }

    /** Sets the new password. The person signs in afterwards (every old session is revoked). */
    @PostMapping("/reset")
    public Map<String, String> reset(@Valid @RequestBody ResetPasswordRequest request) {
        service.reset(request.getEmail(), request.getOtp(), request.getPassword());
        return Map.of("message", "Your password has been changed. Sign in with the new password.");
    }
}