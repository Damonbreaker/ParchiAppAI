package com.kirana.community.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePoolRequest(
        @NotNull Long communityId,

        @Size(max = 80) String title,

        @Min(5) @Max(30) Integer windowMinutes) {
}
