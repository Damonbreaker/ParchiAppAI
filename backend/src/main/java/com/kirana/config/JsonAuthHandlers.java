package com.kirana.config;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.common.ApiError;

import jakarta.servlet.http.HttpServletResponse;

/** 401 and 403 responses as {code, message} JSON instead of Spring's empty/HTML bodies. */
final class JsonAuthHandlers {

    private JsonAuthHandlers() {
    }

    static AuthenticationEntryPoint unauthorized(ObjectMapper mapper) {
        return (request, response, ex) -> {
            Object reason = request.getAttribute(JwtAuthFilter.ERROR_ATTR);
            write(mapper, response, HttpStatus.UNAUTHORIZED,
                    new ApiError("UNAUTHORIZED", reason != null ? reason.toString() : "Login required"));
        };
    }

    static AccessDeniedHandler forbidden(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, response, HttpStatus.FORBIDDEN,
                new ApiError("FORBIDDEN", "You do not have access to this resource"));
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, ApiError body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
