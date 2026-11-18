package com.kirana.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.kirana.user.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Creates and verifies HS256 tokens: subject = user id, claim "role". */
@Component
public class JwtUtil {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration expiry;

    public JwtUtil(JwtProperties props) {
        byte[] secret = props.secret() == null ? new byte[0] : props.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.expiry = Duration.ofDays(props.expiryDays());
    }

    public String createToken(Long userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @throws io.jsonwebtoken.ExpiredJwtException if expired
     * @throws JwtException if the token is invalid in any other way
     */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        try {
            return new AuthUser(Long.valueOf(claims.getSubject()), Role.valueOf(claims.get(ROLE_CLAIM, String.class)));
        } catch (RuntimeException e) {
            throw new JwtException("Malformed token claims", e);
        }
    }
}
