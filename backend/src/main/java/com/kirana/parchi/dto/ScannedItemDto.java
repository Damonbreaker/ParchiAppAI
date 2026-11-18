package com.kirana.parchi.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.kirana.common.Unit;
import com.kirana.parchi.ParchiLineParser.ParsedItem;

/**
 * One scanned row.
 * Customer: base name + qty (+ optional MRP estimate).
 * Shopkeeper: may also include OCR price column.
 */
public record ScannedItemDto(
        String name,
        BigDecimal quantity,
        Unit unit,
        boolean confident,
        String catalogueId,
        String brand,
        String department,
        String category,
        String type,
        String pack,
        String baseName,
        String mode,
        Map<String, Object> mrp,
        BigDecimal price,
        CatalogueOptionsDto options,
        Map<String, Object> selection) {

    public static ScannedItemDto from(ParsedItem p) {
        return new ScannedItemDto(p.name(), p.quantity(), p.unit(), p.confident(),
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
