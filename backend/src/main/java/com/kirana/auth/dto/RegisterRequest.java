package com.kirana.auth.dto;

import com.kirana.user.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String name,

        @NotBlank(message = "is required")
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit mobile number starting with 6-9")
        String phone,

        @NotBlank(message = "is required")
        @Size(min = 6, max = 72, message = "must be 6 to 72 characters")
        String password,

        @NotNull(message = "is required")
        Role role) {
}
