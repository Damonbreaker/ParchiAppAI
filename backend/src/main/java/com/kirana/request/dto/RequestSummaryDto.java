package com.kirana.request.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.kirana.quote.Quote;
import com.kirana.request.OrderRequest;
import com.kirana.request.RequestItem;
import com.kirana.request.RequestMode;
import com.kirana.request.RequestStatus;

/**
 * One row in the customer's request list (also their order history). lowestTotal is null while there are
 * no quotes; acceptedStoreName, acceptedTotal and readyBy are null until a quote is accepted.
 */
public record RequestSummaryDto(Long id, Instant createdAt, RequestStatus status, RequestMode mode,
                                int itemCount, int storesSentCount, int quotesCount, BigDecimal lowestTotal,
                                String acceptedStoreName, BigDecimal acceptedTotal, Instant readyBy,
                                List<String> preview) {

    public static final int PREVIEW_SIZE = 3;

    public static RequestSummaryDto from(OrderRequest r, int quotesCount, BigDecimal lowestTotal, Quote accepted) {
        return new RequestSummaryDto(r.getId(), r.getCreatedAt(), r.getStatus(), r.getMode(),
                r.getItems().size(), r.getTargets().size(), quotesCount, lowestTotal,
                accepted == null ? null : accepted.getStore().getName(),
                accepted == null ? null : accepted.getTotalPrice(),
                RequestDetailDto.readyBy(r, accepted),
                preview(r.getItems()));
    }

    public static List<String> preview(List<RequestItem> items) {
        return items.stream().limit(PREVIEW_SIZE).map(RequestItem::getName).toList();
    }
}
