package photography.photography.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendOtpRequest(@NotBlank(message = "Email is required") @Email(message = "Please provide a valid email address") @Size(max = 254) String email) {
}
