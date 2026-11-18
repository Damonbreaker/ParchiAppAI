package com.kirana.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.auth.dto.AuthResponse;
import com.kirana.auth.dto.LoginRequest;
import com.kirana.auth.dto.RegisterRequest;
import com.kirana.common.ApiException;
import com.kirana.common.RateLimitProperties;
import com.kirana.common.RateLimiter;
import com.kirana.config.JwtUtil;
import com.kirana.user.Lang;
import com.kirana.user.User;
import com.kirana.user.UserRepository;
import com.kirana.user.dto.UserDto;

@Service
public class AuthService {

    private static final String BAD_LOGIN = "Phone number or password is incorrect";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;
    /** Compared against when the phone is unknown, so both failure paths take the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtUtil jwtUtil, RateLimiter rateLimiter,
                       RateLimitProperties limits) {
        this.users = users;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.dummyHash = encoder.encode("timing-dummy-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (users.existsByPhone(req.phone())) {
            throw phoneTaken();
        }
        User user = new User();
        user.setName(req.name().trim());
        user.setPhone(req.phone());
        user.setPasswordHash(encoder.encode(req.password()));
        user.setRole(req.role());
        user.setPreferredLang(Lang.EN);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw phoneTaken(); // two registrations raced past the exists check
        }
        return toResponse(user);
    }

    /**
     * After app.rate-limits.login-failures (5) failed logins for one phone within login-window (15 min),
     * every login for that phone gets 429 TOO_MANY_ATTEMPTS, even with the right password, until the oldest
     * failure leaves the window. A successful login clears the count. Unknown phones are counted the same way.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String key = "login:" + req.phone();
        rateLimiter.check(key, limits.loginFailures(), limits.loginWindow(),
                "Too many failed logins for this number. Try again later.");
        User user = users.findByPhone(req.phone()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean ok = encoder.matches(req.password(), hash);
        if (user == null || !ok) {
            rateLimiter.record(key, limits.loginWindow());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", BAD_LOGIN);
        }
        rateLimiter.reset(key);
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(jwtUtil.createToken(user.getId(), user.getRole()), UserDto.from(user));
    }

    private static ApiException phoneTaken() {
        return new ApiException(HttpStatus.CONFLICT, "PHONE_TAKEN", "This phone number is already registered");
    }
}
