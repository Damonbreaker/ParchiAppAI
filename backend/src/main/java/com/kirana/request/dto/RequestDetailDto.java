package com.kirana.request.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kirana.common.Geo;
import com.kirana.quote.Quote;
import com.kirana.quote.dto.CustomerQuoteDto;
import com.kirana.request.OrderRequest;
import com.kirana.request.RequestMode;
import com.kirana.request.RequestSource;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;

/**
 * Customer's view of their own request, with every quote received (cheapest first, then fastest).
 * Store phones appear only in acceptedStore, and only after the customer accepts that store's quote.
 * The accept fields (acceptedQuoteId ... acceptedStore) are null until then.
 */
public record RequestDetailDto(Long id, RequestStatus status, RequestMode mode, RequestSource source,
                               double lat, double lng, Instant createdAt, Instant updatedAt,
                               Long acceptedQuoteId, String pickupCode, Instant acceptedAt, Instant readyBy,
                               Instant readyAt, Instant pickedUpAt, StoreContactDto acceptedStore,
                               List<ItemDto> items, List<TargetDto> targets, List<CustomerQuoteDto> quotes) {

    public record TargetDto(Long storeId, String storeName, String area, Double distanceKm, Boolean seen,
                            Boolean quoted) {

        static TargetDto from(RequestTarget t, Set<Long> quotedStoreIds) {
            return new TargetDto(t.getStore().getId(), t.getStore().getName(), t.getStore().getArea(),
                    distance(t), t.getSeenAt() != null, quotedStoreIds.contains(t.getStore().getId()));
        }
    }

    public static RequestDetailDto from(OrderRequest r, List<Quote> quotes) {
        Map<Long, RequestTarget> byStore = r.getTargets().stream()
                .collect(Collectors.toMap(t -> t.getStore().getId(), Function.identity()));
        Set<Long> quoted = quotes.stream().map(q -> q.getStore().getId()).collect(Collectors.toSet());
        List<CustomerQuoteDto> quoteDtos = quotes.stream()
                .sorted(Comparator.comparing(Quote::getTotalPrice).thenComparingInt(Quote::getPackingTimeMins)
                        .thenComparing(Quote::getId))
                .map(q -> {
                    RequestTarget t = byStore.get(q.getStore().getId());
                    return CustomerQuoteDto.from(q, t == null ? null : distance(t));
                })
                .toList();
        Quote accepted = acceptedQuote(r, quotes);
        return new RequestDetailDto(r.getId(), r.getStatus(), r.getMode(), r.getSource(),
                r.getLat(), r.getLng(),
                r.getCreatedAt(), r.getUpdatedAt(),
                r.getAcceptedQuoteId(), r.getPickupCode(), r.getAcceptedAt(), readyBy(r, accepted),
                r.getReadyAt(), r.getPickedUpAt(),
                accepted == null ? null : StoreContactDto.from(accepted.getStore()),
                r.getItems().stream().map(ItemDto::from).toList(),
                r.getTargets().stream().map(t -> TargetDto.from(t, quoted)).toList(),
                quoteDtos);
    }

    /** The accepted quote among the request's quotes, or null before accept. */
    static Quote acceptedQuote(OrderRequest r, List<Quote> quotes) {
        if (r.getAcceptedQuoteId() == null) {
            return null;
        }
        return quotes.stream().filter(q -> q.getId().equals(r.getAcceptedQuoteId())).findFirst().orElse(null);
    }

    /** accepted_at + the accepted quote's packing time; null before accept. */
    public static Instant readyBy(OrderRequest r, Quote accepted) {
        if (accepted == null || r.getAcceptedAt() == null) {
            return null;
        }
        return r.getAcceptedAt().plusSeconds(60L * accepted.getPackingTimeMins());
    }

    private static Double distance(RequestTarget t) {
        return Geo.round1(t.getDistanceKm().doubleValue());
    }
}
