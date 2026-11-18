package com.kirana.shared.dto;

import java.time.Instant;
import java.util.List;

import com.kirana.shared.SharedListStatus;

public record SharedListDto(
        String token,
        String title,
        SharedListStatus status,
        String ownerLabel,
        boolean owner,
        Instant expiresAt,
        Instant updatedAt,
        long version,
        int itemCount,
        List<SharedListItemDto> items) {
}
