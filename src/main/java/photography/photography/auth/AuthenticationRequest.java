package photography.photography.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationRequest {

    /** Username or e-mail address. */
    @NotBlank
    @Size(max = 254)
    private String username;
    @NotBlank
    @Size(max = 128)
    private String password;
}
