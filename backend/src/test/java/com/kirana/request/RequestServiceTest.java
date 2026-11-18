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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import com.kirana.common.ApiException;
import com.kirana.common.RateLimitProperties;
import com.kirana.common.RateLimiter;
import com.kirana.common.Unit;
import com.kirana.community.PoolService;
import com.kirana.config.DemoAutoQuoteService;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteRepository;
import com.kirana.quote.QuoteStatus;
import com.kirana.request.dto.CreateRequestRequest;
import com.kirana.request.dto.ItemInput;
import com.kirana.store.StoreRepository;
import com.kirana.store.StoreService;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

class RequestServiceTest {

    private final OrderRequestRepository requests = mock(OrderRequestRepository.class);
    private final RequestTargetRepository targets = mock(RequestTargetRepository.class);
    private final StoreService storeService = mock(StoreService.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final QuoteRepository quotes = mock(QuoteRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DemoAutoQuoteService> demoAutoQuote = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PoolService> poolService = mock(ObjectProvider.class);
    private final RequestService service = new RequestService(requests, targets, storeService, stores, users, quotes,
            new RateLimiter(), RateLimitProperties.defaults(), demoAutoQuote, poolService);

    @Test
    void cancelMovesOpenToCancelled() {
        OrderRequest r = request(RequestStatus.OPEN);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(r));
        when(requests.saveAndFlush(r)).thenReturn(r);

        assertThat(service.cancel(7L, 1L).status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(r.getStatus()).isEqualTo(RequestStatus.CANCELLED);
    }

    @Test
    void cancelRejectsSubmittedQuotes() {
        OrderRequest r = request(RequestStatus.OPEN);
        Quote submitted = quote(10L, QuoteStatus.SUBMITTED);
        Quote alreadyRejected = quote(11L, QuoteStatus.REJECTED);
        when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(r));
        when(requests.saveAndFlush(r)).thenReturn(r);
        when(quotes.findByRequestId(1L)).thenReturn(List.of(submitted, alreadyRejected));

        service.cancel(7L, 1L);

        assertThat(submitted.getStatus()).isEqualTo(QuoteStatus.REJECTED);
        assertThat(submitted.getUpdatedAt()).isNotNull();
        assertThat(alreadyRejected.getUpdatedAt()).isNull(); // untouched
    }

    @Test
    void cancelFromAnyOtherStatusIs409() {
        for (RequestStatus s : List.of(RequestStatus.CANCELLED, RequestStatus.ACCEPTED, RequestStatus.READY,
                RequestStatus.PICKED_UP)) {
            OrderRequest r = request(s);
            when(requests.findOwnForUpdate(1L, 7L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.cancel(7L, 1L))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo("INVALID_STATUS");
                    });
            assertThat(r.getStatus()).isEqualTo(s);
        }
    }

    @Test
    void nearestWithNoStoresIs422AndSavesNothing() {
        when(users.findById(7L)).thenReturn(Optional.of(new User()));
        when(storeService.findNearestOpen(28.5, 77.38, 5)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(7L, new CreateRequestRequest(
                List.of(new ItemInput("Atta", BigDecimal.ONE, Unit.KG)), 28.5, 77.38, RequestMode.NEAREST, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("NO_STORES_NEARBY");
                });
        verify(requests, never()).saveAndFlush(any());
    }

    private static Quote quote(Long id, QuoteStatus status) {
        Quote q = new Quote();
        q.setId(id);
        q.setStatus(status);
        q.setStore(new com.kirana.store.Store());
        q.setTotalPrice(java.math.BigDecimal.TEN);
        return q;
    }

    private static OrderRequest request(RequestStatus status) {
        OrderRequest r = new OrderRequest();
        r.setId(1L);
        r.setStatus(status);
        r.setMode(RequestMode.NEAREST);
        r.setSource(RequestSource.MANUAL);
        return r;
    }
}
