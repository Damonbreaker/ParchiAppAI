package com.kirana.store.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStoreRequest(
        @NotBlank(message = "is required")
        @Size(max = 120, message = "must be at most 120 characters")
        String name,

        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String area,

        /** Optional; defaults to the owner's phone. */
        @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit mobile number starting with 6-9")
        String phone,

        @NotNull(message = "is required")
        @DecimalMin(value = "-90.0", message = "must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "must be between -90 and 90")
        Double lat,

        @NotNull(message = "is required")
        @DecimalMin(value = "-180.0", message = "must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "must be between -180 and 180")
        Double lng) {
}
