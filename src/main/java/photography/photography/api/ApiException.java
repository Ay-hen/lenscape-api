package photography.photography.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Business-rule failure with the error contract the Angular client understands:
 * HTTP status + {@code code} (VALIDATION, NOT_FOUND, CONFLICT, SLOT_UNAVAILABLE, PRICE_CHANGED, ...) + message + optional field errors.
 * Deliberately free of Spring types so the domain layer can throw it.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> errors;
    private long retryAfterSeconds;

    public ApiException(int status, String code, String message, Map<String, String> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> errors() {
        return errors;
    }

    /** 429 with a Retry-After hint (seconds) that the handler turns into the HTTP header and a body field. */
    public static ApiException rateLimited(String message, long retryAfterSeconds) {
        ApiException e = new ApiException(429, "RATE_LIMITED", message, null);
        e.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        return e;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException serviceUnavailable(String message) {
        return new ApiException(503, "UNAVAILABLE", message, null);
    }

    public static ApiException validation(String message) {
        return new ApiException(400, "VALIDATION", message, null);
    }

    public static ApiException validation(String message, String field, String fieldMessage) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put(field, fieldMessage);
        return new ApiException(400, "VALIDATION", message, e);
    }

    public static ApiException unauthenticated(String message) {
        return new ApiException(401, "UNAUTHENTICATED", message, null);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message, null);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message, null);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message, null);
    }

    public static ApiException conflict(String message, String field, String fieldMessage) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put(field, fieldMessage);
        return new ApiException(409, "CONFLICT", message, e);
    }

    public static ApiException notImplemented(String message) {
        return new ApiException(501, "NOT_IMPLEMENTED", message, null);
    }
}
