package com.kirana.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kirana.common.ApiException;
import com.kirana.common.Geo;
import com.kirana.community.PoolService;
import com.kirana.config.DemoOrderProgressService;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteItemStatus;
import com.kirana.quote.QuoteRepository;
import com.kirana.quote.QuoteStatus;
import com.kirana.quote.dto.CustomerQuoteDto;
import com.kirana.request.dto.AcceptRequest;
import com.kirana.request.dto.RequestDetailDto;
import com.kirana.request.dto.StoreRequestDetailDto;
import com.kirana.store.StoreService;

import lombok.RequiredArgsConstructor;

/**
 * Status changes after quoting: accept (customer), ready and picked up (winning store).
 * Each method first takes the request row lock (SELECT ... FOR UPDATE), the same lock quote saves and
 * cancel take, so at most one of them changes a request at a time and each sees the others' result.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Wrong pickup codes allowed before the order is locked. */
    static final int MAX_PICKUP_ATTEMPTS = 5;
    static final Duration PICKUP_LOCK = Duration.ofMinutes(15);

    private final OrderRequestRepository requests;
    private final QuoteRepository quotes;
    private final RequestService requestService;
    private final StoreService storeService;
    private final ObjectProvider<DemoOrderProgressService> demoProgress;
    private final ObjectProvider<PoolService> poolService;

    /**
     * OPEN -> ACCEPTED: the chosen quote -> ACCEPTED, every other quote -> REJECTED, pickup code generated.
     * 409 QUOTE_CHANGED (nothing changed) if the quote's total is no longer what the customer saw.
     */
    @Transactional
    public RequestDetailDto accept(Long customerId, Long requestId, Long quoteId, AcceptRequest body) {
        OrderRequest r = requests.findOwnForUpdate(requestId, customerId).orElseThrow(RequestService::notFound);
        if (r.getStatus() != RequestStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_OPEN",
                    "This request is no longer open (it is " + r.getStatus() + ")");
        }
        List<Quote> all = quotes.findByRequestId(r.getId());
        Quote chosen = all.stream()
                .filter(q -> q.getId().equals(quoteId) && q.getStatus() == QuoteStatus.SUBMITTED)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote not found"));
        if (chosen.getTotalPrice().compareTo(body.expectedTotal()) != 0) {
            RequestTarget t = r.getTargets().stream()
                    .filter(x -> x.getStore().getId().equals(chosen.getStore().getId())).findFirst().orElse(null);
            throw ApiException.withQuote(HttpStatus.CONFLICT, "QUOTE_CHANGED",
                    "The store changed this quote. Please check the new total (Rs " + chosen.getTotalPrice()
                            + ") and accept again.",
                    CustomerQuoteDto.from(chosen, t == null ? null : Geo.round1(t.getDistanceKm().doubleValue())));
        }
        if (chosen.getItems().stream().allMatch(i -> i.getStatus() == QuoteItemStatus.UNAVAILABLE)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_AVAILABLE",
                    "This store has none of your items available");
        }

        Instant now = now();
        r.setStatus(RequestStatus.ACCEPTED);
        r.setAcceptedQuoteId(chosen.getId());
        r.setAcceptedAt(now);
        r.setPickupCode(newPickupCode());
        for (Quote q : all) {
            QuoteStatus next = q == chosen ? QuoteStatus.ACCEPTED : QuoteStatus.REJECTED;
            if (q.getStatus() != next) {
                q.setStatus(next);
                q.setUpdatedAt(now);
            }
        }
        requests.saveAndFlush(r);
        PoolService pools = poolService.getIfAvailable();
        boolean towerPool = pools != null && pools.isPoolRequest(r.getId());
        if (pools != null) {
            pools.onRequestAccepted(r.getId());
        }
        // Demo auto READY→PICKUP skipped for tower pools (per-bag OTP + approve).
        Long ownerId = chosen.getStore().getOwner().getId();
        Long acceptedRequestId = r.getId();
        DemoOrderProgressService progress = towerPool ? null : demoProgress.getIfAvailable();
        if (progress != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    progress.scheduleAfterAccept(acceptedRequestId, ownerId);
                }
            });
        } else if (progress != null) {
            progress.scheduleAfterAccept(acceptedRequestId, ownerId);
        }
        return RequestDetailDto.from(r, all);
    }

    /** ACCEPTED -> READY, winning store only. */
    @Transactional
    public StoreRequestDetailDto markReady(Long ownerId, Long requestId) {
        Long storeId = requestService.ownStoreId(ownerId);
        OrderRequest r = lockWonBy(storeId, requestId);
        if (r.getStatus() != RequestStatus.ACCEPTED) {
            throw invalidStatus("Only accepted orders can be marked ready", r);
        }
        r.setStatus(RequestStatus.READY);
        r.setReadyAt(now());
        requests.saveAndFlush(r);
        PoolService pools = poolService.getIfAvailable();
        if (pools != null) {
            pools.onRequestReady(r.getId());
        }
        return requestService.storeView(storeId, requestId);
    }

    /** Tower pool: deliver one anonymous neighbour bag with that bag's OTP. */
    @Transactional
    public StoreRequestDetailDto markBagDelivered(Long ownerId, Long requestId, Long memberId, String pickupCode) {
        Long storeId = requestService.ownStoreId(ownerId);
        lockWonBy(storeId, requestId);
        PoolService pools = poolService.getIfAvailable();
        if (pools == null || !pools.isPoolRequest(requestId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_POOL", "Use normal pickup for non-pool orders");
        }
        pools.deliverBag(requestId, ownerId, memberId, pickupCode);
        // When every bag is delivered, close the parent order.
        if (pools.shopBags(requestId).stream().allMatch(b -> b.delivered())) {
            OrderRequest r = requests.findByIdForUpdate(requestId).orElseThrow(RequestService::notFound);
            if (r.getStatus() != RequestStatus.PICKED_UP) {
                r.setStatus(RequestStatus.PICKED_UP);
                r.setPickedUpAt(now());
                requests.saveAndFlush(r);
                Quote paid = quotes.findById(r.getAcceptedQuoteId()).orElse(null);
                if (paid != null && paid.getTotalPrice() != null) {
                    storeService.deductParchiFee(storeId, paid.getTotalPrice());
                }
            }
        }
        return requestService.storeView(storeId, requestId);
    }

    /**
     * ACCEPTED or READY -> PICKED_UP, winning store only, with the customer's pickup code.
     * After MAX_PICKUP_ATTEMPTS wrong codes the order is locked for PICKUP_LOCK: every try gets 429
     * TOO_MANY_ATTEMPTS. The counter lives on the request row and is changed under its row lock, so parallel
     * tries are counted one after another. noRollbackFor: the wrong-code count must be saved although the
     * call ends in an error (every other ApiException here is thrown before anything changes).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StoreRequestDetailDto markPickedUp(Long ownerId, Long requestId, String pickupCode) {
        Long storeId = requestService.ownStoreId(ownerId);
        OrderRequest r = lockWonBy(storeId, requestId);
        if (r.getStatus() != RequestStatus.ACCEPTED && r.getStatus() != RequestStatus.READY) {
            throw invalidStatus("Only accepted or ready orders can be marked picked up", r);
        }
        Instant now = now();
        if (r.getPickupLockedUntil() != null) {
            if (now.isBefore(r.getPickupLockedUntil())) {
                long wait = Duration.between(now, r.getPickupLockedUntil()).toMillis();
                throw ApiException.tooManyAttempts("Too many wrong pickup codes. Try again later.",
                        (wait + 999) / 1000);
            }
            r.setPickupLockedUntil(null); // lock over: a fresh set of tries
            r.setPickupFailedAttempts(0);
        }
        if (!codesMatch(r.getPickupCode(), pickupCode)) {
            int failed = r.getPickupFailedAttempts() + 1;
            r.setPickupFailedAttempts(failed);
            int left = MAX_PICKUP_ATTEMPTS - failed;
            if (left <= 0) {
                r.setPickupLockedUntil(now.plus(PICKUP_LOCK));
            }
            requests.saveAndFlush(r);
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_PICKUP_CODE",
                    "Pickup code is wrong. Ask the customer to show the code in their app. "
                            + (left > 0 ? left + (left == 1 ? " try" : " tries") + " left."
                                        : "Locked for " + PICKUP_LOCK.toMinutes() + " minutes."));
        }
        r.setPickupFailedAttempts(0);
        r.setPickupLockedUntil(null);
        r.setStatus(RequestStatus.PICKED_UP);
        r.setPickedUpAt(now);
        requests.saveAndFlush(r);
        // Payment collected at pickup → charge 1% of bill from shopkeeper Parchi wallet.
        Quote paid = quotes.findById(r.getAcceptedQuoteId()).orElse(null);
        if (paid != null && paid.getTotalPrice() != null) {
            storeService.deductParchiFee(storeId, paid.getTotalPrice());
        }
        return requestService.storeView(storeId, requestId);
    }

    // ------------------------------------------------------------ helpers

    /** Locks the request; 404 unless this store's quote is the accepted one (losing stores included). */
    private OrderRequest lockWonBy(Long storeId, Long requestId) {
        OrderRequest r = requests.findByIdForUpdate(requestId).orElseThrow(RequestService::notFound);
        if (r.getAcceptedQuoteId() == null) {
            throw RequestService.notFound();
        }
        Quote accepted = quotes.findById(r.getAcceptedQuoteId()).orElseThrow(RequestService::notFound);
        if (!accepted.getStore().getId().equals(storeId)) {
            throw RequestService.notFound();
        }
        return r;
    }

    private static ApiException invalidStatus(String message, OrderRequest r) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", message + " (this one is " + r.getStatus() + ")");
    }

    /** Postgres stores microseconds; truncating here keeps the response equal to what a later GET returns. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static String newPickupCode() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    /** Constant-time comparison. */
    static boolean codesMatch(String expected, String given) {
        if (expected == null || given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
