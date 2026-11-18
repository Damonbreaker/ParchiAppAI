package com.kirana.quote.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT /api/store/requests/{id}/quote body. Any total sent by the client is ignored. */
public record QuoteInput(
        @NotNull @Min(value = 5, message = "must be between 5 and 240")
        @Max(value = 240, message = "must be between 5 and 240") Integer packingTimeMins,
        @NotNull @Size(min = 1, max = 50, message = "must have 1 to 50 items") List<@Valid @NotNull QuoteItemInput> items) {
}
