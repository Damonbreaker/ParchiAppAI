package com.kirana.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kirana.common.ApiException;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteItem;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteRepository;
import com.kirana.quote.QuoteStatus;
import org.springframework.beans.factory.ObjectProvider;

import com.kirana.community.PoolService;
import com.kirana.config.DemoOrderProgressService;
import com.kirana.request.dto.AcceptRequest;
import com.kirana.store.Store;
import com.kirana.store.StoreService;

class OrderServiceTest {

    private final OrderRequestRepository requests = mock(OrderRequestRepository.class);
    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final RequestService requestService = mock(RequestService.class);
    private final StoreService storeService = mock(StoreService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DemoOrderProgressService> demoProgress = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PoolService> poolService = mock(ObjectProvider.class);
    private final OrderService service = new OrderService(requests, quotes, requestService, storeService, demoProgress,
            poolService);

    private final Store storeA = store(3L);
    private final Store storeB = store(4L);

    @Test
    void acceptMovesRequestAndQuotes() {
        OrderRequest r = request(RequestStatus.OPEN);
        Quote a = quote(10L, storeA, "380.00", QuoteItemStatus.AVAILABLE);
        Quote b = quote(11L, storeB, "400.00", QuoteItemStatus.AVAILABLE);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(r));
        when(quotes.findByRequestId(1L)).thenReturn(List.of(a, b));

        service.accept(7L, 1L, 10L, new AcceptRequest(new BigDecimal("380")));

        assertThat(r.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(r.getAcceptedQuoteId()).isEqualTo(10L);
        assertThat(r.getAcceptedAt()).isNotNull();
        assertThat(r.getPickupCode()).matches("[0-9]{4}");
        assertThat(a.getStatus()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(b.getStatus()).isEqualTo(QuoteStatus.REJECTED);
    }

    @Test
    void acceptWithChangedTotalChangesNothing() {
        OrderRequest r = request(RequestStatus.OPEN);
        Quote a = quote(10L, storeA, "370.00", QuoteItemStatus.AVAILABLE);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(r));
        when(quotes.findByRequestId(1L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.accept(7L, 1L, 10L, new AcceptRequest(new BigDecimal("380"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("QUOTE_CHANGED");
                    assertThat(e.getQuote()).isNotNull();
                });
        assertThat(r.getStatus()).isEqualTo(RequestStatus.OPEN);
        assertThat(a.getStatus()).isEqualTo(QuoteStatus.SUBMITTED);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void acceptRejectsNotOpenAndNothingAvailable() {
        OrderRequest accepted = request(RequestStatus.ACCEPTED);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(accepted));
        assertThatThrownBy(() -> service.accept(7L, 1L, 10L, new AcceptRequest(BigDecimal.ZERO)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REQUEST_NOT_OPEN"));

        OrderRequest open = request(RequestStatus.OPEN);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(open));
        when(quotes.findByRequestId(1L)).thenReturn(List.of(quote(10L, storeA, "0.00", QuoteItemStatus.UNAVAILABLE)));
        assertThatThrownBy(() -> service.accept(7L, 1L, 10L, new AcceptRequest(BigDecimal.ZERO)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NOTHING_AVAILABLE"));
        assertThat(open.getStatus()).isEqualTo(RequestStatus.OPEN);
    }

    @Test
    void readyOnlyFromAcceptedAndOnlyForWinner() {
        when(requestService.ownStoreId(9L)).thenReturn(3L);
        OrderRequest r = request(RequestStatus.ACCEPTED);
        r.setAcceptedQuoteId(10L);
        when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(quotes.findById(10L)).thenReturn(Optional.of(quote(10L, storeA, "380.00", QuoteItemStatus.AVAILABLE)));

        service.markReady(9L, 1L);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.READY);
        assertThat(r.getReadyAt()).isNotNull();

        assertThatThrownBy(() -> service.markReady(9L, 1L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATUS"));

        when(requestService.ownStoreId(8L)).thenReturn(4L); // losing store
        assertThatThrownBy(() -> service.markReady(8L, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void pickedUpNeedsTheRightCode() {
        when(requestService.ownStoreId(9L)).thenReturn(3L);
        OrderRequest r = request(RequestStatus.READY);
        r.setAcceptedQuoteId(10L);
        r.setPickupCode("0427");
        when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(quotes.findById(10L)).thenReturn(Optional.of(quote(10L, storeA, "380.00", QuoteItemStatus.AVAILABLE)));

        assertThatThrownBy(() -> service.markPickedUp(9L, 1L, "4270"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("WRONG_PICKUP_CODE");
                });
        assertThat(r.getStatus()).isEqualTo(RequestStatus.READY);

        service.markPickedUp(9L, 1L, "0427");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PICKED_UP);
        assertThat(r.getPickedUpAt()).isNotNull();
    }

    @Test
    void fiveWrongCodesLockTheOrderAndARightCodeResets() {
        when(requestService.ownStoreId(9L)).thenReturn(3L);
        OrderRequest r = request(RequestStatus.READY);
        r.setAcceptedQuoteId(10L);
        r.setPickupCode("0427");
        when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(quotes.findById(10L)).thenReturn(Optional.of(quote(10L, storeA, "380.00", QuoteItemStatus.AVAILABLE)));

        // wrong codes 1..4: 400 and counted, no lock yet
        assertThatThrownBy(() -> service.markPickedUp(9L, 1L, "1111")).isInstanceOf(ApiException.class);
        assertThat(r.getPickupFailedAttempts()).isEqualTo(1);
        assertThat(r.getPickupLockedUntil()).isNull();

        for (int i = 2; i <= 5; i++) {
            assertThatThrownBy(() -> service.markPickedUp(9L, 1L, "1111"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("WRONG_PICKUP_CODE"));
        }
        assertThat(r.getPickupFailedAttempts()).isEqualTo(5);
        assertThat(r.getPickupLockedUntil()).isNotNull();

        // locked: even the right code gets 429 with a retry time of about 15 minutes
        assertThatThrownBy(() -> service.markPickedUp(9L, 1L, "0427"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(e.getRetryAfterSeconds()).isBetween(890L, 900L);
                });
        assertThat(r.getStatus()).isEqualTo(RequestStatus.READY);

        // lock expired: a fresh start, and the right code works and clears the counter
        r.setPickupLockedUntil(java.time.Instant.now().minusSeconds(1));
        service.markPickedUp(9L, 1L, "0427");
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PICKED_UP);
        assertThat(r.getPickupFailedAttempts()).isZero();
        assertThat(r.getPickupLockedUntil()).isNull();
    }

    @Test
    void rightCodeAfterSomeWrongOnesResetsTheCounter() {
        when(requestService.ownStoreId(9L)).thenReturn(3L);
        OrderRequest r = request(RequestStatus.ACCEPTED);
        r.setAcceptedQuoteId(10L);
        r.setPickupCode("0427");
        r.setPickupFailedAttempts(4);
        when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(quotes.findById(10L)).thenReturn(Optional.of(quote(10L, storeA, "380.00", QuoteItemStatus.AVAILABLE)));

        service.markPickedUp(9L, 1L, "0427");
        assertThat(r.getPickupFailedAttempts()).isZero();
        assertThat(r.getStatus()).isEqualTo(RequestStatus.PICKED_UP);
    }

    @Test
    void pickupCodesAreFourDigits() {
        for (int i = 0; i < 200; i++) {
            assertThat(OrderService.newPickupCode()).matches("[0-9]{4}");
        }
        assertThat(OrderService.codesMatch("0427", "0427")).isTrue();
        assertThat(OrderService.codesMatch("0427", "427")).isFalse();
        assertThat(OrderService.codesMatch(null, "0427")).isFalse();
    }

    private static OrderRequest request(RequestStatus status) {
        OrderRequest r = new OrderRequest();
        r.setId(1L);
        r.setStatus(status);
        return r;
    }

    private static Store store(Long id) {
        Store s = new Store();
        s.setId(id);
        return s;
    }

    private static Quote quote(Long id, Store store, String total, QuoteItemStatus lineStatus) {
        Quote q = new Quote();
        q.setId(id);
        q.setStore(store);
        q.setStatus(QuoteStatus.SUBMITTED);
        q.setTotalPrice(new BigDecimal(total));
        QuoteItem line = new QuoteItem();
        line.setStatus(lineStatus);
        RequestItem asked = new RequestItem();
        asked.setQuantity(BigDecimal.ONE);
        asked.setUnit(com.kirana.common.Unit.KG);
        line.setRequestItem(asked);
        line.setUnit(com.kirana.common.Unit.KG);
        line.setOfferedQty(BigDecimal.ONE);
        line.setLinePrice(new BigDecimal(total));
        q.addItem(line);
        return q;
    }
}
