package com.kirana.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kirana.common.ApiException;
import com.kirana.common.Unit;
import com.kirana.quote.dto.QuoteInput;
import com.kirana.quote.dto.QuoteItemInput;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.RequestItem;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;
import com.kirana.request.RequestTargetRepository;
import com.kirana.store.Store;
import com.kirana.store.StoreRepository;

class QuoteServiceTest {

    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final OrderRequestRepository requests = mock(OrderRequestRepository.class);
    private final RequestTargetRepository targets = mock(RequestTargetRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final QuoteService service = new QuoteService(quotes, requests, targets, stores);

    @Test
    void quotingANonOpenRequestIs409AndSavesNothing() {
        Store store = new Store();
        store.setId(3L);
        when(stores.findByOwnerId(9L)).thenReturn(Optional.of(store));
        when(targets.findByRequestIdAndStoreId(1L, 3L)).thenReturn(Optional.of(new RequestTarget()));
        for (RequestStatus s : List.of(RequestStatus.CANCELLED, RequestStatus.ACCEPTED)) {
            OrderRequest r = new OrderRequest();
            r.setId(1L);
            r.setStatus(s);
            when(requests.findByIdForUpdate(1L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.upsert(9L, 1L, new QuoteInput(10, List.of(
                    new QuoteItemInput(1L, BigDecimal.ONE, Unit.KG, BigDecimal.TEN, QuoteItemStatus.AVAILABLE)))))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getCode()).isEqualTo("REQUEST_CLOSED");
                    });
        }
        verify(quotes, never()).saveAndFlush(any());
    }

    @Test
    void validateForcesUnavailableToZeroAndKeepsAskedUnit() {
        RequestItem atta = item(1L, Unit.KG);
        RequestItem eggs = item(2L, Unit.DOZEN);
        List<QuoteService.Line> lines = QuoteService.validate(List.of(
                new QuoteItemInput(1L, new BigDecimal("5"), Unit.KG, new BigDecimal("280.5"), QuoteItemStatus.AVAILABLE),
                new QuoteItemInput(2L, new BigDecimal("12"), null, new BigDecimal("99"), QuoteItemStatus.UNAVAILABLE)),
                Map.of(1L, atta, 2L, eggs));

        assertThat(lines.get(0).linePrice()).isEqualByComparingTo("280.50");
        assertThat(lines.get(1).offeredQty()).isZero();
        assertThat(lines.get(1).linePrice()).isZero();
        assertThat(lines.get(1).unit()).isEqualTo(Unit.DOZEN);
    }

    @Test
    void validateRejectsAllUnavailableAndBadLines() {
        RequestItem atta = item(1L, Unit.KG);
        RequestItem eggs = item(2L, Unit.DOZEN);

        assertThatThrownBy(() -> QuoteService.validate(List.of(
                new QuoteItemInput(1L, null, null, null, QuoteItemStatus.UNAVAILABLE),
                new QuoteItemInput(2L, null, null, null, QuoteItemStatus.UNAVAILABLE)),
                Map.of(1L, atta, 2L, eggs)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getFields()).containsKey("items"));

        assertThatThrownBy(() -> QuoteService.validate(List.of(
                new QuoteItemInput(1L, BigDecimal.ZERO, Unit.KG, new BigDecimal("1.234"), QuoteItemStatus.PARTIAL),
                new QuoteItemInput(1L, BigDecimal.ONE, Unit.KG, BigDecimal.ONE, QuoteItemStatus.AVAILABLE)),
                Map.of(1L, atta, 2L, eggs)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getFields())
                        .containsEntry("items[0].offeredQty", "must be greater than 0")
                        .containsEntry("items[0].linePrice", "must have at most 2 decimals")
                        .containsEntry("items[1].requestItemId", "appears more than once")
                        .containsKey("items"));
    }

    private static RequestItem item(Long id, Unit unit) {
        RequestItem i = new RequestItem();
        i.setId(id);
        i.setUnit(unit);
        return i;
    }
}
