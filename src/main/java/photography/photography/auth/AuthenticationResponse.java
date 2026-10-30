package photography.photography.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import photography.photography.account.UserDto;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthenticationResponse {

    private String token;
    private String role;
    /** The signed-in account, so the client does not need a second round trip after login/registration. */
    private UserDto user;
}
