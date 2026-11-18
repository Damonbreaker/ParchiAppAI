package com.kirana.store.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record TopUpParchiRequest(
        @NotNull @DecimalMin(value = "1.00", message = "must be at least 1") BigDecimal amount) {
}
