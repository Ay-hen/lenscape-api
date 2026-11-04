package photography.photography.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record ResetPasswordRequest(
        @NotBlank(message = "Email is required") @Email(message = "Please provide a valid email address") @Size(max = 254) String email,
        @NotBlank(message = "The reset token is required") @Size(min = 20, max = 128, message = "The reset token is invalid") String resetToken,
        @NotBlank(message = "Password is required") @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password) {
}
