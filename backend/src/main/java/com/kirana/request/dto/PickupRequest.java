package com.kirana.request.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** POST /api/store/requests/{id}/picked-up. The code the customer shows at the counter. */
public record PickupRequest(@NotNull @Pattern(regexp = "[0-9]{4}", message = "must be 4 digits") String pickupCode) {
}
