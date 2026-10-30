package photography.photography.auth;

/** Answer of POST /user/auth/register: the account does not exist yet; a 6-digit code was e-mailed. */
public record RegistrationResponse(String message, String email, boolean verificationRequired, long otpExpiresInSeconds) {
}
