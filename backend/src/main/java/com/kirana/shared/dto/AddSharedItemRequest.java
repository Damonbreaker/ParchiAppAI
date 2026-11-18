package com.kirana.shared.dto;

import java.math.BigDecimal;

import com.kirana.common.Unit;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddSharedItemRequest(
        @NotBlank(message = "is required")
        @Size(max = 60, message = "must be at most 60 characters")
        String name,

        @NotNull(message = "is required")
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @Digits(integer = 7, fraction = 3, message = "must have at most 3 decimals")
        BigDecimal quantity,

        @NotNull(message = "is required")
        Unit unit,

        @Size(max = 40, message = "must be at most 40 characters")
        String guestName,

        @Size(max = 64, message = "must be at most 64 characters")
        String guestKey) {
}
