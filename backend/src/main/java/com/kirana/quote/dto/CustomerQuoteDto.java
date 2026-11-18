package com.kirana.quote.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.kirana.common.Unit;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteStatus;
import com.kirana.request.RequestItem;

/** A quote as the customer sees it. No store phone (revealed after accept, Phase 5). */
public record CustomerQuoteDto(Long quoteId, Long storeId, String storeName, String area, Double distanceKm,
                               QuoteStatus status, BigDecimal totalPrice, int packingTimeMins,
                               int availableCount, int partialCount, int unavailableCount, Instant updatedAt,
                               List<LineDto> items) {

    public record LineDto(Long requestItemId, String name, BigDecimal askedQty, Unit askedUnit,
                          BigDecimal offeredQty, Unit unit, BigDecimal linePrice, QuoteItemStatus status) {
    }

    public static CustomerQuoteDto from(Quote q, Double distanceKm) {
        List<LineDto> lines = QuoteDto.sortedLines(q).stream().map(i -> {
            RequestItem asked = i.getRequestItem();
            return new LineDto(asked.getId(), asked.getName(), asked.getQuantity().stripTrailingZeros(),
                    asked.getUnit(), i.getOfferedQty().stripTrailingZeros(), i.getUnit(), i.getLinePrice(),
                    i.getStatus());
        }).toList();
        return new CustomerQuoteDto(q.getId(), q.getStore().getId(), q.getStore().getName(), q.getStore().getArea(),
                distanceKm, q.getStatus(), q.getTotalPrice(), q.getPackingTimeMins(),
                count(lines, QuoteItemStatus.AVAILABLE), count(lines, QuoteItemStatus.PARTIAL),
                count(lines, QuoteItemStatus.UNAVAILABLE), q.getUpdatedAt(), lines);
    }

    private static int count(List<LineDto> lines, QuoteItemStatus s) {
        return (int) lines.stream().filter(l -> l.status() == s).count();
    }
}
