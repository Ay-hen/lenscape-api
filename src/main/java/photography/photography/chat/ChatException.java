package photography.photography.chat;

import org.springframework.http.HttpStatus;

/** Business-rule failure with a stable machine-readable {@code code} (the front-end maps it to its ApiError codes). */
public class ChatException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static ChatException forbidden(String message) {
        return new ChatException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ChatException notFound(String message) {
        return new ChatException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ChatException invalid(String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "VALIDATION", message);
    }

    public static ChatException tooMany(String message) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }
}
