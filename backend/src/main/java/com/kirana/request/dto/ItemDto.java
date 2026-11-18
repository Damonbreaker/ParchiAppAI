package com.kirana.request.dto;

import java.math.BigDecimal;

import com.kirana.common.Unit;
import com.kirana.request.RequestItem;

/** quantity is sent without trailing zeros: 0.5, 2, 250. */
public record ItemDto(Long id, String name, BigDecimal quantity, Unit unit, int position) {

    public static ItemDto from(RequestItem i) {
        return new ItemDto(i.getId(), i.getName(), i.getQuantity().stripTrailingZeros(), i.getUnit(), i.getPosition());
    }
}
