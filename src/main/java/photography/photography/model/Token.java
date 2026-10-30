package photography.photography.model;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Document
public class Token {
    @Id
    private String token;

    private boolean revoked;

    private boolean expired;

    private User user;

    /** Owner of the token, so every session can be revoked at once (password change). */
    @Indexed
    private String username;

    /** MongoDB removes the document at this instant (TTL index), so tokens do not pile up forever. */
    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;
}