package com.kirana.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.kirana.auth.dto.AuthResponse;
import com.kirana.auth.dto.LoginRequest;
import com.kirana.auth.dto.RegisterRequest;
import com.kirana.common.ApiException;
import com.kirana.common.RateLimitProperties;
import com.kirana.common.RateLimiter;
import com.kirana.config.JwtProperties;
import com.kirana.config.JwtUtil;
import com.kirana.user.Lang;
import com.kirana.user.Role;
import com.kirana.user.User;
import com.kirana.user.UserRepository;
import org.mockito.Mockito;

class AuthServiceTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: fast tests
    private final JwtUtil jwt = new JwtUtil(new JwtProperties("test-secret-test-secret-test-secret-123", 7));
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, jwt, new RateLimiter(), RateLimitProperties.defaults());
    }

    @Test
    void registerHashesPasswordAndReturnsToken() {
        when(users.existsByPhone("9876543210")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        AuthResponse res = service.register(new RegisterRequest("  रमेश कुमार ", "9876543210", "secret1", Role.CUSTOMER));

        assertThat(res.user().id()).isEqualTo(42L);
        assertThat(res.user().name()).isEqualTo("रमेश कुमार");
        assertThat(res.user().preferredLang()).isEqualTo(Lang.EN);
        assertThat(jwt.parse(res.token()).id()).isEqualTo(42L);
        assertThat(jwt.parse(res.token()).role()).isEqualTo(Role.CUSTOMER);
        verify(users).saveAndFlush(Mockito.argThat(u -> encoder.matches("secret1", u.getPasswordHash())
                && !u.getPasswordHash().equals("secret1")));
    }

    @Test
    void registerDuplicatePhoneIs409() {
        when(users.existsByPhone("9876543210")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("A", "9876543210", "secret1", Role.CUSTOMER)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("PHONE_TAKEN");
                });
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void loginSuccessReturnsToken() {
        when(users.findByPhone("9876543210")).thenReturn(Optional.of(user(7L, "secret1")));

        AuthResponse res = service.login(new LoginRequest("9876543210", "secret1"));

        assertThat(res.user().id()).isEqualTo(7L);
        assertThat(jwt.parse(res.token()).role()).isEqualTo(Role.STORE_OWNER);
    }

    @Test
    void loginWrongPasswordAndUnknownPhoneGiveSameError() {
        when(users.findByPhone("9876543210")).thenReturn(Optional.of(user(7L, "secret1")));
        when(users.findByPhone("9000000000")).thenReturn(Optional.empty());

        ApiException wrongPw = catchApi(() -> service.login(new LoginRequest("9876543210", "nope123")));
        ApiException noUser = catchApi(() -> service.login(new LoginRequest("9000000000", "secret1")));

        assertThat(wrongPw.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPw.getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(noUser.getCode()).isEqualTo(wrongPw.getCode());
        assertThat(noUser.getMessage()).isEqualTo(wrongPw.getMessage());
    }

    private User user(Long id, String rawPassword) {
        User u = new User();
        u.setId(id);
        u.setName("Store Owner");
        u.setPhone("9876543210");
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRole(Role.STORE_OWNER);
        return u;
    }

    private static ApiException catchApi(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected ApiException");
    }
}
