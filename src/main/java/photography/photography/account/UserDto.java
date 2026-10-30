package photography.photography.account;

import java.time.Instant;

import photography.photography.model.User;

/** The account as the Angular {@code User}. {@code id} is the username (the primary key of the user collection). Never carries secrets. */
public record UserDto(String id, String role, String name, String username, String email, String phone, String avatarUrl, String city,
                      String status, Instant createdAt, Instant lastActiveAt) {

    /** @param photographerApproval approval state of the photographer profile, or null for other roles */
    public static UserDto of(User u, String photographerApproval) {
        String status = u.isSuspended() ? "suspended"
                : "photographer".equals(u.getRole()) && !"approved".equals(photographerApproval) ? "pending" : "active";
        return new UserDto(u.getUsername(), u.getRole(), u.getName(), u.getUsername(), u.getEmail(), u.getPhone(), u.getAvatarUrl(), u.getCity(),
                status, u.getCreatedAt(), u.getLastActiveAt());
    }
}
