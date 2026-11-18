package com.kirana.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // Exact origins (http://localhost:5173) and patterns (https://*.netlify.app).
        List<String> exact = allowedOrigins.stream().filter(o -> o != null && !o.isBlank() && !o.contains("*")).toList();
        List<String> patterns = allowedOrigins.stream().filter(o -> o != null && o.contains("*")).toList();
        if (!exact.isEmpty()) {
            config.setAllowedOrigins(exact);
        }
        if (!patterns.isEmpty()) {
            config.setAllowedOriginPatterns(patterns);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
