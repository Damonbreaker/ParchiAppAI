package com.kirana.config;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads "Authorization: Bearer <token>". A valid token sets the security context;
 * a bad one leaves it empty and records why, so the 401 handler can say so.
 * Not a @Component on purpose: Boot would otherwise also register it as a servlet filter.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    static final String ERROR_ATTR = "kirana.jwtError";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            try {
                AuthUser user = jwtUtil.parse(header.substring(PREFIX.length()).trim());
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ExpiredJwtException e) {
                request.setAttribute(ERROR_ATTR, "Token expired, please log in again");
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(ERROR_ATTR, "Invalid token");
            }
        }
        chain.doFilter(request, response);
    }
}
