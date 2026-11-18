package com.kirana.rating.dto;

import java.util.List;

public record StoreRatingSummaryDto(
        Double avgStars,
        long ratingCount,
        List<String> topQualities) {

    public static StoreRatingSummaryDto empty() {
        return new StoreRatingSummaryDto(null, 0, List.of());
    }
}
