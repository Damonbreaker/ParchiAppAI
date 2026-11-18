package com.kirana.request.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** POST /api/requests/{id}/accept/{quoteId}. expectedTotal = the total the customer saw on screen. */
public record AcceptRequest(
        @NotNull @DecimalMin(value = "0", message = "must be 0 or more")
        @Digits(integer = 8, fraction = 2, message = "must have at most 2 decimals") BigDecimal expectedTotal) {
}
