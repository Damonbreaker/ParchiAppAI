package com.kirana.rating.dto;

import java.time.Instant;
import java.util.List;

import com.kirana.rating.Rating;
import com.kirana.rating.RatingTargetType;

public record RatingDto(
        Long id,
        Long requestId,
        RatingTargetType targetType,
        int stars,
        List<String> qualities,
        Instant createdAt) {

    public static RatingDto from(Rating r, List<String> qualities) {
        return new RatingDto(r.getId(), r.getRequest().getId(), r.getTargetType(), r.getStars(),
                qualities, r.getCreatedAt());
    }
}
