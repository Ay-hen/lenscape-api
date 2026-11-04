package photography.photography.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import photography.photography.verification.PasswordResetService;
import photography.photography.verification.PasswordResetService.ResetGrant;

/**
 * Password recovery (public: the person cannot sign in). Three stages, each authorising the next:
 * {@code /forgot} (e-mail a code) -> {@code /verify} (code -> single-use reset token) -> {@code /reset} (token + new password).
 */
@RestController
@RequestMapping("/auth/password")
public class PasswordResetController {
    private static final String CODE_SENT = "If an account uses this email, we sent a 6-digit code to it.";

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** Always answers the same text, whether or not an account uses that address. */
    @PostMapping("/forgot")
    public Map<String, Object> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        return codeSent(request.email());
    }

    /** Sends a new code (the previous one stops working). Same answer as /forgot; the cooldown and the hourly cap are enforced silently. */
    @PostMapping("/resend")
    public Map<String, Object> resend(@Valid @RequestBody ForgotPasswordRequest request) {
        return codeSent(request.email());
    }

    /** Checks the e-mailed code. On success returns the single-use, short-lived token that /reset requires. Wrong/expired/used codes: 400 INVALID_CODE. */
    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody VerifyResetCodeRequest request) {
        ResetGrant grant = service.verifyCode(request.email(), request.otp());
        return Map.of("resetToken", grant.resetToken(), "expiresInSeconds", grant.expiresInSeconds());
    }

    /** Sets the new password. The person signs in afterwards (every old session is revoked). Missing/expired/used token: 400 INVALID_RESET_TOKEN. */
    @PostMapping("/reset")
    public Map<String, String> reset(@Valid @RequestBody ResetPasswordRequest request) {
        service.reset(request.email(), request.resetToken(), request.password());
        return Map.of("message", "Your password has been changed. Sign in with the new password.");
    }

    private Map<String, Object> codeSent(String email) {
        service.request(email);
        return Map.of("message", CODE_SENT, "expiresInSeconds", service.otpTtlSeconds(), "resendAfterSeconds", service.resendCooldownSeconds());
    }
}