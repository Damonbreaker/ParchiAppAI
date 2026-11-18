package com.kirana.rating.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RatingInput(
        @NotNull @Min(1) @Max(5) Integer stars,
        List<String> qualities) {
}
