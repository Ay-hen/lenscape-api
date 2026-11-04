package photography.photography.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /auth/password/verify. */
public record VerifyResetCodeRequest(
        @NotBlank(message = "Email is required") @Email(message = "Please provide a valid email address") @Size(max = 254) String email,
        @NotBlank(message = "Code is required") @Pattern(regexp = "\\d{6}", message = "The code has exactly 6 digits") String otp) {
}
