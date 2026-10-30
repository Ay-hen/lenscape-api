package photography.photography.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(@NotBlank @Size(max = 128) String current, @NotBlank @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String next) {
}
