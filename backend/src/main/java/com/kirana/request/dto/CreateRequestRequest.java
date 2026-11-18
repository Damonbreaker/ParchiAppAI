package com.kirana.request.dto;

import java.util.List;

import com.kirana.request.RequestMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRequestRequest(
        @NotNull(message = "is required")
        @Size(min = 1, max = 50, message = "must have 1 to 50 items")
        List<@Valid @NotNull(message = "must not be null") ItemInput> items,

        @NotNull(message = "is required")
        @DecimalMin(value = "-90.0", message = "must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "must be between -90 and 90")
        Double lat,

        @NotNull(message = "is required")
        @DecimalMin(value = "-180.0", message = "must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "must be between -180 and 180")
        Double lng,

        @NotNull(message = "is required")
        RequestMode mode,

        /** Required for DIRECT, must be absent for NEAREST (checked in RequestService). */
        Long storeId,

        /** true if the list came from POST /api/parchi/scan (source PHOTO); absent or false = typed (MANUAL). */
        Boolean fromPhoto) {
}
