package com.kirana.shared.dto;

import java.math.BigDecimal;

import com.kirana.common.Unit;

public record SharedListItemDto(
        Long id,
        String name,
        BigDecimal quantity,
        Unit unit,
        int position,
        String addedByLabel,
        boolean mine) {
}
