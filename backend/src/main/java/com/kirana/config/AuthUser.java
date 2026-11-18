package com.kirana.config;

import com.kirana.user.Role;

/** The logged-in user, taken from the JWT. Inject in controllers with @AuthenticationPrincipal. */
public record AuthUser(Long id, Role role) {
}
