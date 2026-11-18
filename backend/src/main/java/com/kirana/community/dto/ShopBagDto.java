package com.kirana.community.dto;

import java.util.List;

import com.kirana.community.PoolMember;
import com.kirana.community.PoolMemberStatus;

/** Shop packing view — anonymous alias only, never real name/phone. */
public record ShopBagDto(
        Long memberId,
        String alias,
        PoolMemberStatus status,
        boolean fromPhoto,
        List<PoolItemDto> items,
        boolean delivered) {

    public static ShopBagDto from(PoolMember m) {
        return new ShopBagDto(
                m.getId(),
                m.getAlias(),
                m.getStatus(),
                m.isFromPhoto(),
                m.getItems().stream().map(PoolItemDto::from).toList(),
                m.getDeliveredAt() != null);
    }
}
