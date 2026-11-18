package com.kirana.quote.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.kirana.common.Unit;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteItem;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteStatus;

/** The store's own quote (PUT response and myQuote). Lines follow the request's item order. */
public record QuoteDto(Long quoteId, Long requestId, QuoteStatus status, BigDecimal totalPrice, int packingTimeMins,
                       Instant createdAt, Instant updatedAt, List<LineDto> items) {

    public record LineDto(Long requestItemId, BigDecimal offeredQty, Unit unit, BigDecimal linePrice,
                          QuoteItemStatus status) {
    }

    public static QuoteDto from(Quote q) {
        return new QuoteDto(q.getId(), q.getRequest().getId(), q.getStatus(), q.getTotalPrice(),
                q.getPackingTimeMins(), q.getCreatedAt(), q.getUpdatedAt(),
                sortedLines(q).stream().map(i -> new LineDto(i.getRequestItem().getId(),
                        i.getOfferedQty().stripTrailingZeros(), i.getUnit(), i.getLinePrice(), i.getStatus()))
                        .toList());
    }

    static List<QuoteItem> sortedLines(Quote q) {
        return q.getItems().stream()
                .sorted(Comparator.comparingInt(i -> i.getRequestItem().getPosition()))
                .toList();
    }
}
