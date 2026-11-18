package com.kirana.community.dto;

import java.math.BigDecimal;

import com.kirana.common.Unit;
import com.kirana.community.PoolMemberItem;

public record PoolItemDto(String name, BigDecimal quantity, Unit unit) {

    public static PoolItemDto from(PoolMemberItem item) {
        return new PoolItemDto(item.getName(), item.getQuantity(), item.getUnit());
    }
}
