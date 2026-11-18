package com.kirana.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.Unit;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteService;
import com.kirana.quote.dto.QuoteInput;
import com.kirana.quote.dto.QuoteItemInput;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.RequestItem;
import com.kirana.request.RequestStatus;
import com.kirana.request.RequestTarget;
import com.kirana.store.Store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo helper: after a customer sends a list, several nearby shops auto-submit
 * different prices so testers can compare and accept one without logging in as
 * each shopkeeper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoAutoQuoteService {

    /** Price multipliers so 4–5 shops look competitive, not identical. */
    private static final double[] PRICE_FACTORS = { 0.94, 1.00, 1.06, 1.12, 1.18 };
    private static final int[] PACKING_MINS = { 15, 20, 18, 25, 30 };

    private final OrderRequestRepository requests;
    private final QuoteService quoteService;

    @Value("${app.demo.auto-quote:true}")
    private boolean enabled;

    @Value("${app.demo.auto-quote-max-stores:5}")
    private int maxStores;

    @Transactional
    public void maybeAutoQuote(Long requestId) {
        if (!enabled || requestId == null) {
            return;
        }
        OrderRequest request = requests.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.OPEN) {
            return;
        }
        List<RequestTarget> targets = request.getTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }
        int limit = Math.min(Math.max(maxStores, 1), PRICE_FACTORS.length);
        int quoted = 0;
        for (int i = 0; i < targets.size() && quoted < limit; i++) {
            RequestTarget target = targets.get(i);
            Store store = target.getStore();
            if (store == null || store.getOwner() == null) {
                continue;
            }
            double factor = PRICE_FACTORS[quoted];
            int packing = PACKING_MINS[quoted];
            List<QuoteItemInput> lines = new ArrayList<>();
            for (RequestItem item : request.getItems()) {
                BigDecimal linePrice = estimateLine(item)
                        .multiply(BigDecimal.valueOf(factor))
                        .setScale(2, RoundingMode.HALF_UP);
                lines.add(new QuoteItemInput(
                        item.getId(),
                        item.getQuantity(),
                        item.getUnit(),
                        linePrice,
                        QuoteItemStatus.AVAILABLE));
            }
            try {
                quoteService.upsert(store.getOwner().getId(), requestId, new QuoteInput(packing, lines));
                quoted++;
                log.info("Demo auto-quote from {} (#{}/{}) for request #{}",
                        store.getName(), quoted, limit, requestId);
            } catch (Exception ex) {
                log.warn("Demo auto-quote skipped for {} on request #{}: {}",
                        store.getName(), requestId, ex.getMessage());
            }
        }
    }

    /** Rough kirana demo prices so the quote total looks believable. */
    static BigDecimal estimateLine(RequestItem item) {
        BigDecimal qty = item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
        Unit unit = item.getUnit() == null ? Unit.PCS : item.getUnit();
        BigDecimal per = switch (unit) {
            case KG -> BigDecimal.valueOf(75);
            case G -> BigDecimal.valueOf(0.08);
            case L -> BigDecimal.valueOf(58);
            case ML -> BigDecimal.valueOf(0.06);
            case DOZEN -> BigDecimal.valueOf(72);
            case PACKET -> BigDecimal.valueOf(38);
            case PCS -> BigDecimal.valueOf(18);
        };
        String name = (item.getName() == null ? "" : item.getName()).toLowerCase(Locale.ROOT);
        if (name.contains("milk") || name.contains("doodh")) {
            per = unit == Unit.L ? BigDecimal.valueOf(56) : unit == Unit.ML ? BigDecimal.valueOf(0.056) : per;
        } else if (name.contains("atta") || name.contains("aata")) {
            per = unit == Unit.KG ? BigDecimal.valueOf(48) : per;
        } else if (name.contains("rice") || name.contains("chawal")) {
            per = unit == Unit.KG ? BigDecimal.valueOf(70) : per;
        } else if (name.contains("oil") || name.contains("tel")) {
            per = unit == Unit.L ? BigDecimal.valueOf(145) : per;
        } else if (name.contains("egg")) {
            per = unit == Unit.DOZEN ? BigDecimal.valueOf(78) : BigDecimal.valueOf(7);
        }
        return qty.multiply(per);
    }
}
