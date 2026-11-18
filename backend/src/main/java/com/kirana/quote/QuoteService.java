package com.kirana.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.common.Unit;
import com.kirana.quote.dto.QuoteDto;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuoteService {

    /** linePrice limits: up to ₹9,99,999.99 per line, so 50 lines always fit NUMERIC(10,2). */
    static final int PRICE_INTEGER_DIGITS = 6;
    static final int PRICE_FRACTION_DIGITS = 2;
    /** offeredQty limits, same as the asked quantity in Phase 3. */
    static final int QTY_INTEGER_DIGITS = 7;
    static final int QTY_FRACTION_DIGITS = 3;

    private final QuoteRepository quotes;
    private final OrderRequestRepository requests;
    private final RequestTargetRepository targets;
    private final StoreRepository stores;

    /**
     * Creates or updates this owner's store quote for the request. The request row is locked
     * (SELECT ... FOR UPDATE) for the whole transaction, so a quote can never be written after the
     * request has left OPEN (cancel and, in Phase 5, accept take the same lock).
     */
    @Transactional
    public QuoteDto upsert(Long ownerId, Long requestId, QuoteInput input) {
        Store store = stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_STORE",
                        "You have not set up your store yet"));
        RequestTarget target = targets.findByRequestIdAndStoreId(requestId, store.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found"));

        OrderRequest request = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found"));
        if (request.getStatus() != RequestStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED",
                    "This request is no longer open for quotes (it is " + request.getStatus() + ")");
        }

        Map<Long, RequestItem> asked = request.getItems().stream()
                .collect(Collectors.toMap(RequestItem::getId, Function.identity()));
        List<Line> lines = validate(input.items(), asked);

        Quote quote = quotes.findByRequestIdAndStoreId(requestId, store.getId()).orElseGet(() -> {
            Quote q = new Quote();
            q.setRequest(request);
            q.setStore(store);
            return q;
        });
        Map<Long, QuoteItem> existing = new HashMap<>();
        quote.getItems().forEach(i -> existing.put(i.getRequestItem().getId(), i));

        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            // Update rows in place: the set of request items never changes, so no delete+insert churn.
            QuoteItem qi = existing.get(line.item().getId());
            if (qi == null) {
                qi = new QuoteItem();
                qi.setRequestItem(line.item());
                quote.addItem(qi);
            }
            qi.setStatus(line.status());
            qi.setOfferedQty(line.offeredQty());
            qi.setUnit(line.unit());
            qi.setLinePrice(line.linePrice());
            total = total.add(line.linePrice());
        }
        quote.setTotalPrice(total.setScale(2, RoundingMode.UNNECESSARY));
        quote.setPackingTimeMins(input.packingTimeMins());
        quote.setStatus(QuoteStatus.SUBMITTED);
        quote.setUpdatedAt(Instant.now());

        if (target.getSeenAt() == null) {
            target.setSeenAt(Instant.now()); // quoting implies the owner has seen it
        }
        return QuoteDto.from(quotes.saveAndFlush(quote));
    }

    record Line(RequestItem item, QuoteItemStatus status, BigDecimal offeredQty,
                Unit unit, BigDecimal linePrice) {
    }

    /** Every request item exactly once; per-line rules depend on status. Collects all problems at once. */
    static List<Line> validate(List<QuoteItemInput> inputs, Map<Long, RequestItem> asked) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        List<Line> lines = new ArrayList<>();

        for (int i = 0; i < inputs.size(); i++) {
            QuoteItemInput in = inputs.get(i);
            String p = "items[" + i + "].";
            RequestItem item = asked.get(in.requestItemId());
            if (item == null) {
                errors.put(p + "requestItemId", "is not an item of this request");
                continue;
            }
            if (!seen.add(in.requestItemId())) {
                errors.put(p + "requestItemId", "appears more than once");
                continue;
            }
            if (in.status() == QuoteItemStatus.UNAVAILABLE) {
                // Whatever was sent is ignored: nothing offered, nothing charged.
                lines.add(new Line(item, in.status(), BigDecimal.ZERO.setScale(QTY_FRACTION_DIGITS),
                        in.unit() != null ? in.unit() : item.getUnit(),
                        BigDecimal.ZERO.setScale(PRICE_FRACTION_DIGITS)));
                continue;
            }
            boolean ok = checkPositive(errors, p + "offeredQty", in.offeredQty(), QTY_INTEGER_DIGITS,
                    QTY_FRACTION_DIGITS);
            ok &= checkPositive(errors, p + "linePrice", in.linePrice(), PRICE_INTEGER_DIGITS,
                    PRICE_FRACTION_DIGITS);
            if (in.unit() == null) {
                errors.put(p + "unit", "is required");
                ok = false;
            }
            if (ok) {
                lines.add(new Line(item, in.status(), in.offeredQty().setScale(QTY_FRACTION_DIGITS),
                        in.unit(), in.linePrice().setScale(PRICE_FRACTION_DIGITS)));
            }
        }

        List<Long> missing = asked.keySet().stream().filter(id -> !seen.contains(id)).sorted().toList();
        if (!missing.isEmpty()) {
            errors.put("items", "missing requestItemId " + missing.stream().map(String::valueOf)
                    .collect(Collectors.joining(", ")) + " (every item of the request must be answered once)");
        } else if (errors.isEmpty() && lines.stream().allMatch(l -> l.status() == QuoteItemStatus.UNAVAILABLE)) {
            errors.put("items", "at least one item must be AVAILABLE or PARTIAL");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return lines;
    }

    private static boolean checkPositive(Map<String, String> errors, String field, BigDecimal v, int integer,
                                         int fraction) {
        if (v == null) {
            errors.put(field, "is required");
            return false;
        }
        if (v.signum() <= 0) {
            errors.put(field, "must be greater than 0");
            return false;
        }
        BigDecimal s = v.stripTrailingZeros();
        if (Math.max(s.scale(), 0) > fraction) {
            errors.put(field, "must have at most " + fraction + " decimals");
            return false;
        }
        if (s.precision() - s.scale() > integer) {
            errors.put(field, "is too large");
            return false;
        }
        return true;
    }
}
