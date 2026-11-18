package com.kirana.request.dto;

import java.time.Instant;
import java.util.List;

import com.kirana.common.Geo;
import com.kirana.community.dto.ShopBagDto;
import com.kirana.quote.Quote;
import com.kirana.quote.dto.QuoteDto;
import com.kirana.request.OrderRequest;
import com.kirana.request.RequestMode;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;
import com.kirana.request.StoreOutcome;

/**
 * Store owner's view of a request sent to them, plus their own quote (null if not quoted).
 * For tower pools, {@code bags} lists anonymous neighbour bags to pack separately.
 * Customer name/phone are hidden for pool orders (community label only).
 */
public record StoreRequestDetailDto(Long id, RequestStatus status, RequestMode mode, Instant createdAt,
                                    Double distanceKm, Instant seenAt, List<ItemDto> items, QuoteDto myQuote,
                                    StoreOutcome outcome, CustomerContactDto customer, Instant acceptedAt,
                                    Instant readyBy, Instant readyAt, Instant pickedUpAt,
                                    String pickupCode, Instant pickupCodeAvailableAt,
                                    String poolLabel, List<ShopBagDto> bags) {

    public static StoreRequestDetailDto from(RequestTarget t, Quote myQuote) {
        return from(t, myQuote, null, null, null, List.of());
    }

    public static StoreRequestDetailDto from(RequestTarget t, Quote myQuote, String pickupCode,
                                             Instant pickupCodeAvailableAt) {
        return from(t, myQuote, pickupCode, pickupCodeAvailableAt, null, List.of());
    }

    public static StoreRequestDetailDto from(RequestTarget t, Quote myQuote, String pickupCode,
                                             Instant pickupCodeAvailableAt, String poolLabel,
                                             List<ShopBagDto> bags) {
        OrderRequest r = t.getRequest();
        StoreOutcome outcome = outcome(r, myQuote);
        boolean won = outcome == StoreOutcome.WON;
        boolean pool = poolLabel != null && bags != null && !bags.isEmpty();
        CustomerContactDto contact = null;
        if (won) {
            contact = pool
                    ? new CustomerContactDto(poolLabel, "Tower pool · aliases only")
                    : CustomerContactDto.from(r.getCustomer());
        }
        String code = pool ? null : (won ? pickupCode : null);
        return new StoreRequestDetailDto(r.getId(), r.getStatus(), r.getMode(), r.getCreatedAt(),
                Geo.round1(t.getDistanceKm().doubleValue()), t.getSeenAt(),
                r.getItems().stream().map(ItemDto::from).toList(),
                myQuote == null ? null : QuoteDto.from(myQuote),
                outcome,
                contact,
                won ? r.getAcceptedAt() : null,
                won ? RequestDetailDto.readyBy(r, myQuote) : null,
                won ? r.getReadyAt() : null,
                won ? r.getPickedUpAt() : null,
                code,
                won && !pool ? pickupCodeAvailableAt : null,
                poolLabel,
                bags == null ? List.of() : bags);
    }

    /** null while OPEN; CANCELLED; WON if this store's quote is the accepted one; otherwise LOST. */
    public static StoreOutcome outcome(OrderRequest r, Quote myQuote) {
        return switch (r.getStatus()) {
            case OPEN, DRAFT -> null;
            case CANCELLED -> StoreOutcome.CANCELLED;
            case ACCEPTED, READY, PICKED_UP -> myQuote != null && myQuote.getId().equals(r.getAcceptedQuoteId())
                    ? StoreOutcome.WON
                    : StoreOutcome.LOST;
        };
    }
}
