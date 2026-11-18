package com.kirana.common;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String VALIDATION_MSG = "Please correct the highlighted fields";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            res.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return res.body(new ApiError(ex.getCode(), ex.getMessage(), ex.getFields(), ex.getQuote(),
                ex.getRetryAfterSeconds()));
    }

    /** Constraint on a @RequestParam / @PathVariable, e.g. lat=200. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParamValidation(HandlerMethodValidationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (ParameterValidationResult r : ex.getParameterValidationResults()) {
            String name = r.getMethodParameter().getParameterName();
            r.getResolvableErrors().stream().findFirst()
                    .ifPresent(e -> fields.putIfAbsent(name, e.getDefaultMessage()));
        }
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", VALIDATION_MSG, fields));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(
                new ApiError("VALIDATION_ERROR", VALIDATION_MSG, Map.of(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(
                new ApiError("VALIDATION_ERROR", VALIDATION_MSG, Map.of(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", VALIDATION_MSG, fields));
    }

    /** Bad JSON, or a wrong value type for a field (e.g. role: "ADMIN", preferredLang: "fr"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jsonPath(jme);
            String msg = "has an invalid value";
            if (jme instanceof InvalidFormatException ife && ife.getTargetType() != null
                    && ife.getTargetType().isEnum()) {
                msg = "must be one of " + Arrays.toString(ife.getTargetType().getEnumConstants());
            }
            if (field != null) {
                return ResponseEntity.badRequest()
                        .body(new ApiError("VALIDATION_ERROR", VALIDATION_MSG, Map.of(field, msg)));
            }
        }
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed JSON body");
    }

    /** Two writers changed the same row at once (JPA @Version); the loser gets 409. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT", "This was changed by someone else just now. Please refresh.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
        // From @PreAuthorize; URL rules are handled by the security filter chain.
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have access to this resource");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send Content-Type: application/json");
    }

    /** Upload over spring.servlet.multipart.max-file-size (5 MB): JSON 413, not Spring's default page. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Photo must be 5 MB or smaller");
    }

    /** Not a multipart request, or a broken one, sent to an upload endpoint. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Send the photo as multipart/form-data in the field \"image\"");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return build(status, status.name(), ex.getReason() != null ? ex.getReason() : status.getReasonPhrase());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong");
    }

    /** JSON path in the same style as bean-validation field names, e.g. "items[2].unit". */
    private static String jsonPath(JsonMappingException jme) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : jme.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
