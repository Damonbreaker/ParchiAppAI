package com.kirana.parchi.dto;

import java.util.List;
import java.util.Map;

/** Catalogue poll choices for one scanned row (brand / type / pack / …). */
public record CatalogueOptionsDto(
        List<String> departments,
        List<String> categories,
        List<String> types,
        List<String> brands,
        List<String> products,
        List<Map<String, Object>> packs,
        List<String> units) {
}
