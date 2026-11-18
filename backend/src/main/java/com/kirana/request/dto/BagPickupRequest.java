package com.kirana.request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record BagPickupRequest(
        @NotNull Long memberId,

        @NotBlank
        @Pattern(regexp = "\\d{4}", message = "must be 4 digits")
        String pickupCode) {
}
