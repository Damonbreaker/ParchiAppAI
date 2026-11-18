package com.kirana.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/** Thrown by services for expected failures; GlobalExceptionHandler turns it into {code, message[, fields]}. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;
    /** Extra payload returned as "quote" in the error body (409 QUOTE_CHANGED). */
    private final Object quote;
    /** For 429 TOO_MANY_ATTEMPTS: seconds until the caller may try again (body field and Retry-After header). */
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fields) {
        this(status, code, message, fields, null, null);
    }

    private ApiException(HttpStatus status, String code, String message, Map<String, String> fields, Object quote,
                         Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
        this.quote = quote;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 429 TOO_MANY_ATTEMPTS with retryAfterSeconds (at least 1). */
    public static ApiException tooManyAttempts(String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", message, null, null,
                Math.max(1, retryAfterSeconds));
    }

    /** An error whose body also carries the current quote (409 QUOTE_CHANGED). */
    public static ApiException withQuote(HttpStatus status, String code, String message, Object quote) {
        return new ApiException(status, code, message, null, quote, null);
    }

    public static ApiException validation(Map<String, String> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Please correct the highlighted fields",
                fields);
    }
}
