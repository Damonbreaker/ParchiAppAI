package com.kirana.store.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** All fields optional; only the ones sent are changed. Sent strings must not be blank. */
public record UpdateStoreRequest(
        @Size(max = 120, message = "must be at most 120 characters")
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String name,

        @Size(max = 255, message = "must be at most 255 characters")
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String address,

        @Size(max = 100, message = "must be at most 100 characters")
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String area,

        @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit mobile number starting with 6-9")
        String phone,

        @DecimalMin(value = "-90.0", message = "must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "must be between -90 and 90")
        Double lat,

        @DecimalMin(value = "-180.0", message = "must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "must be between -180 and 180")
        Double lng,

        Boolean isOpen) {
}
