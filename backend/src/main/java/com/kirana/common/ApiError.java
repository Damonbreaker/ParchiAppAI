package com.kirana.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body for every failed request: {"code", "message"}, plus "fields" (field name -> message) for
 * validation errors only, "quote" (the current quote) for 409 QUOTE_CHANGED only, and "retryAfterSeconds"
 * for 429 TOO_MANY_ATTEMPTS only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, String> fields, Object quote,
                       Long retryAfterSeconds) {

    public ApiError(String code, String message) {
        this(code, message, null, null, null);
    }

    public ApiError(String code, String message, Map<String, String> fields) {
        this(code, message, fields, null, null);
    }
}
