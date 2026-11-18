package com.kirana.community.dto;

import java.time.Instant;
import java.util.List;

import com.kirana.community.PoolStatus;

public record PoolDto(
        String token,
        String title,
        PoolStatus status,
        String communityLabel,
        String communityCode,
        int windowMinutes,
        Instant opensAt,
        Instant closesAt,
        long secondsLeft,
        boolean host,
        boolean member,
        String myAlias,
        int memberCount,
        int listsReady,
        Long orderRequestId,
        List<PoolBagDto> bags) {
}
