package photography.photography.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9._-]{3,30}$", message = "3-30 letters, digits, dot, dash or underscore")
    private String username;
    @NotBlank
    @Size(min = 2, max = 80)
    private String name;
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;
    /** BCrypt only uses the first 72 bytes, so longer passwords are refused instead of silently truncated. */
    @NotBlank
    @Size(min = 8, max = 72, message = "Use 8 to 72 characters")
    private String password;
    @Size(max = 30)
    private String phone;
    @Size(max = 60)
    private String city;
    /** Only "client" and "photographer" can be self-registered. Anything else is rejected: administrators are created by the operator. */
    @Pattern(regexp = "^(client|photographer)$", message = "Role must be client or photographer")
    private String role;
}
