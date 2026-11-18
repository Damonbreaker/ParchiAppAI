package com.kirana.request;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.common.Geo;
import com.kirana.common.PageDto;
import com.kirana.common.RateLimitProperties;
import com.kirana.common.RateLimiter;
import com.kirana.community.PoolService;
import com.kirana.community.dto.ShopBagDto;
import com.kirana.config.DemoAutoQuoteService;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteRepository;
import com.kirana.quote.QuoteStatus;
import com.kirana.request.dto.CreateRequestRequest;
import com.kirana.request.dto.ItemInput;
import com.kirana.request.dto.RequestDetailDto;
import com.kirana.request.dto.RequestSummaryDto;
import com.kirana.request.dto.StoreInboxItemDto;
import com.kirana.request.dto.StoreRequestDetailDto;
import com.kirana.store.Store;
import com.kirana.store.StoreDistanceRow;
import com.kirana.store.StoreRepository;
import com.kirana.store.StoreService;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RequestService {

    /** How many stores a NEAREST request goes to (PLAN.md). */
    public static final int NEAREST_LIMIT = 5;
    /** Statuses of an order the winning store still has to hand over. */
    static final List<RequestStatus> ACTIVE_ORDER = List.of(RequestStatus.ACCEPTED, RequestStatus.READY);

    private final OrderRequestRepository requests;
    private final RequestTargetRepository targets;
    private final StoreService storeService;
    private final StoreRepository stores;
    private final UserRepository users;
    private final QuoteRepository quotes;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;
    private final ObjectProvider<DemoAutoQuoteService> demoAutoQuote;
    private final ObjectProvider<PoolService> poolService;

    @Value("${app.demo.reveal-pickup-code:true}")
    private boolean demoRevealPickup;

    @Value("${app.demo.reveal-pickup-after-seconds:4}")
    private int demoRevealPickupAfterSec;

    // ------------------------------------------------------------ customer

    /**
     * Creates the request, its items and its targets in one transaction; status starts at OPEN.
     * At most app.rate-limits.requests (10) created per customer per request-window (1 h); only successful
     * creates count, so a validation error does not use up the quota.
     */
    @Transactional
    public RequestDetailDto create(Long customerId, CreateRequestRequest req) {
        String rateKey = "request:" + customerId;
        rateLimiter.check(rateKey, limits.requests(), limits.requestWindow(),
                "You have sent too many requests. Please try again later.");
        if (req.mode() == RequestMode.DIRECT && req.storeId() == null) {
            throw ApiException.validation(Map.of("storeId", "is required for DIRECT"));
        }
        if (req.mode() == RequestMode.NEAREST && req.storeId() != null) {
            throw ApiException.validation(Map.of("storeId", "only allowed for DIRECT"));
        }
        User customer = users.findById(customerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User no longer exists"));

        List<RequestTarget> chosen = req.mode() == RequestMode.NEAREST
                ? nearestTargets(req.lat(), req.lng())
                : List.of(directTarget(req.storeId(), req.lat(), req.lng()));

        OrderRequest r = new OrderRequest();
        r.setCustomer(customer);
        r.setLat(req.lat());
        r.setLng(req.lng());
        r.setSource(Boolean.TRUE.equals(req.fromPhoto()) ? RequestSource.PHOTO : RequestSource.MANUAL);
        r.setMode(req.mode());
        r.setStatus(RequestStatus.OPEN);
        int position = 1;
        for (ItemInput in : req.items()) {
            RequestItem item = new RequestItem();
            item.setName(in.name().trim());
            item.setQuantity(in.quantity());
            item.setUnit(in.unit());
            item.setPosition(position++);
            r.addItem(item);
        }
        chosen.forEach(r::addTarget);

        RequestDetailDto created = RequestDetailDto.from(requests.saveAndFlush(r), List.of());
        rateLimiter.record(rateKey, limits.requestWindow());
        // Demo: Sharma (9000000011) auto-quotes so testers can proceed without a second login.
        DemoAutoQuoteService autoQuote = demoAutoQuote.getIfAvailable();
        if (autoQuote != null) {
            autoQuote.maybeAutoQuote(created.id());
            return customerView(loadOwn(customerId, created.id()));
        }
        return created;
    }

    /** One page of the customer's requests, newest first. */
    @Transactional(readOnly = true)
    public PageDto<RequestSummaryDto> listMine(Long customerId, int page, int size) {
        Slice<OrderRequest> slice = requests.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId,
                PageRequest.of(page, size));
        List<OrderRequest> mine = slice.getContent();
        if (mine.isEmpty()) {
            return PageDto.of(slice, List.of());
        }
        Map<Long, QuoteRepository.QuoteStats> stats = quotes.statsForRequests(
                        mine.stream().map(OrderRequest::getId).toList()).stream()
                .collect(Collectors.toMap(QuoteRepository.QuoteStats::getRequestId, Function.identity()));
        List<Long> acceptedIds = mine.stream().map(OrderRequest::getAcceptedQuoteId)
                .filter(Objects::nonNull).toList();
        Map<Long, Quote> accepted = acceptedIds.isEmpty() ? Map.of()
                : quotes.findAllById(acceptedIds).stream().collect(Collectors.toMap(Quote::getId, Function.identity()));
        return PageDto.map(slice, r -> {
            QuoteRepository.QuoteStats st = stats.get(r.getId());
            Quote acc = r.getAcceptedQuoteId() == null ? null : accepted.get(r.getAcceptedQuoteId());
            return st == null
                    ? RequestSummaryDto.from(r, 0, null, acc)
                    : RequestSummaryDto.from(r, (int) st.getQuoteCount(), st.getLowestTotal(), acc);
        });
    }

    @Transactional(readOnly = true)
    public RequestDetailDto getMine(Long customerId, Long requestId) {
        return customerView(loadOwn(customerId, requestId));
    }

    /**
     * OPEN -> CANCELLED, and its SUBMITTED quotes -> REJECTED, in one transaction. Any other status is
     * 409 INVALID_STATUS. Takes the request row lock, so it waits for (and then sees) any quote being saved.
     */
    @Transactional
    public RequestDetailDto cancel(Long customerId, Long requestId) {
        OrderRequest r = requests.findOwnForUpdate(requestId, customerId).orElseThrow(RequestService::notFound);
        if (r.getStatus() != RequestStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS",
                    "Only open requests can be cancelled (this one is " + r.getStatus() + ")");
        }
        r.setStatus(RequestStatus.CANCELLED);
        Instant now = Instant.now();
        List<Quote> received = quotes.findByRequestId(r.getId());
        for (Quote q : received) {
            if (q.getStatus() == QuoteStatus.SUBMITTED) {
                q.setStatus(QuoteStatus.REJECTED);
                q.setUpdatedAt(now);
            }
        }
        OrderRequest saved = requests.saveAndFlush(r); // flush now so a version clash surfaces as 409
        return RequestDetailDto.from(saved, received);
    }

    // ------------------------------------------------------------ store owner

    /** One page of the store's inbox (optionally filtered), newest first. */
    @Transactional(readOnly = true)
    public PageDto<StoreInboxItemDto> storeInbox(Long ownerId, InboxFilter filter, int page, int size) {
        Long storeId = ownStoreId(ownerId);
        Pageable p = PageRequest.of(page, size);
        Slice<RequestTarget> slice = filter == null ? targets.findInbox(storeId, p) : switch (filter) {
            case OPEN -> targets.findInboxWithStatus(storeId, RequestStatus.OPEN, p);
            case ORDERS -> targets.findOrders(storeId, ACTIVE_ORDER, p);
            case CLOSED -> targets.findClosed(storeId, RequestStatus.OPEN, ACTIVE_ORDER, p);
        };
        List<RequestTarget> rows = slice.getContent();
        if (rows.isEmpty()) {
            return PageDto.of(slice, List.of());
        }
        Map<Long, Quote> mine = quotes.findByStoreIdAndRequestIdIn(storeId,
                        rows.stream().map(t -> t.getRequest().getId()).toList()).stream()
                .collect(Collectors.toMap(q -> q.getRequest().getId(), Function.identity()));
        return PageDto.map(slice, t -> StoreInboxItemDto.from(t, mine.get(t.getRequest().getId())));
    }

    /** Returns the request if it was sent to this owner's store, and marks it seen on first open. */
    @Transactional
    public StoreRequestDetailDto storeOpen(Long ownerId, Long requestId) {
        Long storeId = ownStoreId(ownerId);
        RequestTarget t = targets.findByRequestIdAndStoreId(requestId, storeId)
                .orElseThrow(RequestService::notFound);
        if (t.getSeenAt() == null) {
            t.setSeenAt(Instant.now());
        }
        Quote myQuote = quotes.findByRequestIdAndStoreId(requestId, storeId).orElse(null);
        return withDemoPickup(t, myQuote);
    }

    // ------------------------------------------------------------ views shared with OrderService

    RequestDetailDto customerView(OrderRequest r) {
        return RequestDetailDto.from(r, quotes.findByRequestId(r.getId()));
    }

    StoreRequestDetailDto storeView(Long storeId, Long requestId) {
        RequestTarget t = targets.findByRequestIdAndStoreId(requestId, storeId).orElseThrow(RequestService::notFound);
        Quote myQuote = quotes.findByRequestIdAndStoreId(requestId, storeId).orElse(null);
        return withDemoPickup(t, myQuote);
    }

    /**
     * Demo helper: after ready (or accept) + N seconds, show the customer's pickup OTP
     * to the winning shopkeeper so a single-device demo can finish the flow.
     */
    private StoreRequestDetailDto withDemoPickup(RequestTarget target, Quote myQuote) {
        OrderRequest r = target.getRequest();
        String poolLabel = null;
        List<ShopBagDto> bags = List.of();
        PoolService pools = poolService.getIfAvailable();
        if (pools != null) {
            bags = pools.shopBags(r.getId());
            if (!bags.isEmpty()) {
                poolLabel = "Tower delivery pool";
                // Per-bag OTP; never reveal a single host code for pool orders.
                return StoreRequestDetailDto.from(target, myQuote, null, null, poolLabel, bags);
            }
        }
        StoreOutcome outcome = StoreRequestDetailDto.outcome(r, myQuote);
        if (!demoRevealPickup || outcome != StoreOutcome.WON) {
            return StoreRequestDetailDto.from(target, myQuote, null, null, poolLabel, bags);
        }
        if (r.getStatus() != RequestStatus.ACCEPTED && r.getStatus() != RequestStatus.READY) {
            return StoreRequestDetailDto.from(target, myQuote, null, null, poolLabel, bags);
        }
        Instant start = r.getReadyAt() != null ? r.getReadyAt() : r.getAcceptedAt();
        if (start == null || r.getPickupCode() == null) {
            return StoreRequestDetailDto.from(target, myQuote, null, null, poolLabel, bags);
        }
        Instant availableAt = start.plusSeconds(Math.max(0, demoRevealPickupAfterSec));
        String code = Instant.now().isBefore(availableAt) ? null : r.getPickupCode();
        return StoreRequestDetailDto.from(target, myQuote, code, availableAt, poolLabel, bags);
    }

    // ------------------------------------------------------------ helpers

    /** Noida demo seed cluster (DevDataSeeder TEST_LAT/LNG). */
    private static final double DEMO_LAT = 28.505;
    private static final double DEMO_LNG = 77.38;

    private List<RequestTarget> nearestTargets(double lat, double lng) {
        // Demo: phones outside the Noida seed cluster (or with no nearby open stores)
        // still route to Sharma / Gupta / … so shopkeeper 9000000011 gets the list.
        double queryLat = lat;
        double queryLng = lng;
        if (Geo.haversineKm(lat, lng, DEMO_LAT, DEMO_LNG) > 5.0) {
            queryLat = DEMO_LAT;
            queryLng = DEMO_LNG;
        }
        List<StoreDistanceRow> rows = storeService.findNearestOpen(queryLat, queryLng, NEAREST_LIMIT);
        if (rows.isEmpty()) {
            rows = storeService.findNearestOpen(DEMO_LAT, DEMO_LNG, NEAREST_LIMIT);
        }
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_STORES_NEARBY",
                    "No open stores found near you. Try searching for a specific store.");
        }
        List<RequestTarget> result = new ArrayList<>();
        for (StoreDistanceRow row : rows) {
            result.add(target(stores.getReferenceById(row.getId()), row.getDistanceKm()));
        }
        return result;
    }

    private RequestTarget directTarget(Long storeId, double lat, double lng) {
        Store s = stores.findById(storeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found"));
        if (!s.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "STORE_CLOSED", "This store is closed right now");
        }
        return target(s, Geo.haversineKm(lat, lng, s.getLat(), s.getLng()));
    }

    private static RequestTarget target(Store store, double distanceKm) {
        RequestTarget t = new RequestTarget();
        t.setStore(store);
        t.setDistanceKm(BigDecimal.valueOf(distanceKm).setScale(2, RoundingMode.HALF_UP));
        return t;
    }

    private OrderRequest loadOwn(Long customerId, Long requestId) {
        // Someone else's request is reported as not found, so ids can't be probed.
        return requests.findByIdAndCustomerId(requestId, customerId).orElseThrow(RequestService::notFound);
    }

    Long ownStoreId(Long ownerId) {
        return stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_STORE",
                        "You have not set up your store yet"))
                .getId();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found");
    }
}
