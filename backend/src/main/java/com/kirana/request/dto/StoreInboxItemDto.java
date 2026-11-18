package com.kirana.request.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.kirana.common.Geo;
import com.kirana.quote.Quote;
import com.kirana.request.OrderRequest;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;
import com.kirana.request.StoreOutcome;

/**
 * One row in a store's inbox. isNew = the owner has not opened it yet; myQuoteTotal null = not quoted.
 * outcome is null while OPEN. customerName and readyBy are filled only when this store won.
 */
public record StoreInboxItemDto(Long id, Instant createdAt, RequestStatus status, int itemCount,
                                Double distanceKm, List<String> preview, Boolean isNew, BigDecimal myQuoteTotal,
                                StoreOutcome outcome, String customerName, Instant readyBy) {

    public static StoreInboxItemDto from(RequestTarget t, Quote myQuote) {
        OrderRequest r = t.getRequest();
        StoreOutcome outcome = StoreRequestDetailDto.outcome(r, myQuote);
        boolean won = outcome == StoreOutcome.WON;
        return new StoreInboxItemDto(r.getId(), r.getCreatedAt(), r.getStatus(), r.getItems().size(),
                Geo.round1(t.getDistanceKm().doubleValue()), RequestSummaryDto.preview(r.getItems()),
                t.getSeenAt() == null, myQuote == null ? null : myQuote.getTotalPrice(),
                outcome,
                won ? r.getCustomer().getName() : null,
                won ? RequestDetailDto.readyBy(r, myQuote) : null);
    }
}
