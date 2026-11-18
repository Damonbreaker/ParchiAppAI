package com.kirana.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stateless JWT security.
 * Role rules by path: /api/store/** = STORE_OWNER, /api/requests/** = CUSTOMER.
 * Mixed-role paths (e.g. /api/stores/**) use @PreAuthorize on the method.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtUtil jwtUtil, ObjectMapper mapper)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(JsonAuthHandlers.unauthorized(mapper))
                        .accessDeniedHandler(JsonAuthHandlers.forbidden(mapper)))
                .addFilterBefore(new JwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        // Group lists: friends can view/edit via share token without login.
                        .requestMatchers(HttpMethod.GET, "/api/shared-lists/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/shared-lists/*/items").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/shared-lists/*/items/*").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/shared-lists/*/items/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/shared-lists").hasRole("CUSTOMER")
                        .requestMatchers("/api/shared-lists/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/communities/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/pools/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/store/**").hasRole("STORE_OWNER")
                        .requestMatchers("/api/requests/**").hasRole("CUSTOMER")
                        // Customer scan + catalogue polls; shop uses /api/store/parchi/**
                        .requestMatchers("/api/parchi/**").hasAnyRole("CUSTOMER", "STORE_OWNER")
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
