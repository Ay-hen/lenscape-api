package photography.photography.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import photography.photography.api.ApiException;
import photography.photography.chat.ChatException;

/**
 * One error contract for the whole API, as the Angular client expects it:
 * {@code { "code": "...", "message": "...", "errors": { "field": "message" } }}. Internal details are logged, never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code());
        m.put("message", e.getMessage());
        if (e.errors() != null && !e.errors().isEmpty()) {
            m.put("errors", e.errors());
        }
        if (e.retryAfterSeconds() > 0) {
            m.put("retryAfterSeconds", e.retryAfterSeconds());
            return ResponseEntity.status(e.status()).header("Retry-After", Long.toString(e.retryAfterSeconds())).body(m);
        }
        return ResponseEntity.status(e.status()).body(m);
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<Map<String, Object>> chat(ChatException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage() == null ? "Invalid value" : f.getDefaultMessage()));
        return api(new ApiException(400, "VALIDATION", "Please fix the highlighted fields.", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", "The request is malformed.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", "The file is too large.");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException e) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "That record already exists.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        HttpStatus s = HttpStatus.resolve(e.getStatusCode().value());
        String code = s == HttpStatus.NOT_FOUND ? "NOT_FOUND" : s == HttpStatus.FORBIDDEN ? "FORBIDDEN" : "ERROR";
        return body(s == null ? HttpStatus.INTERNAL_SERVER_ERROR : s, code, e.getReason() == null ? "Request failed" : e.getReason());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> badCredentials(BadCredentialsException e) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Wrong username or password.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do that.");
    }

    /** Anything unexpected: log it, answer with a generic message. Spring MVC's own errors (405, 415, 404 ...) keep their status. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatus s = HttpStatus.resolve(er.getStatusCode().value());
            if (s != null && s.is4xxClientError()) {
                return body(s, s == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "ERROR", s.getReasonPhrase());
            }
        }
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER", "Something went wrong on our side. Please try again.");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
