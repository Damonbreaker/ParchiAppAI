package com.kirana.rating;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kirana.common.ApiException;
import com.kirana.quote.Quote;
import com.kirana.quote.QuoteRepository;
import com.kirana.rating.dto.RatingDto;
import com.kirana.rating.dto.RatingInput;
import com.kirana.rating.dto.StoreRatingSummaryDto;
import com.kirana.request.OrderRequest;
import com.kirana.request.OrderRequestRepository;
import com.kirana.request.RequestStatus;
import com.kirana.store.Store;
import com.kirana.store.StoreRepository;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RatingService {

    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {
    };

    private final RatingRepository ratings;
    private final OrderRequestRepository requests;
    private final QuoteRepository quotes;
    private final StoreRepository stores;
    private final UserRepository users;
    private final ObjectMapper mapper;

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Request not found");
    }

    @Transactional
    public RatingDto rateStore(Long customerId, Long requestId, RatingInput input) {
        OrderRequest r = requests.findById(requestId).orElseThrow(RatingService::notFound);
        if (!r.getCustomer().getId().equals(customerId)) {
            throw notFound();
        }
        if (r.getStatus() != RequestStatus.PICKED_UP) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS",
                    "You can rate only after pickup");
        }
        if (ratings.existsByRequestIdAndRaterId(requestId, customerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RATED", "You already rated this order");
        }
        Quote accepted = quotes.findById(r.getAcceptedQuoteId()).orElseThrow(RatingService::notFound);
        Store store = accepted.getStore();
        User rater = users.findById(customerId).orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User no longer exists"));

        List<String> qualities = RatingQualities.sanitize(RatingTargetType.STORE, input.qualities());
        Rating rating = new Rating();
        rating.setRequest(r);
        rating.setRater(rater);
        rating.setTargetType(RatingTargetType.STORE);
        rating.setStore(store);
        rating.setStars(input.stars());
        rating.setQualities(writeQualities(qualities));
        return RatingDto.from(ratings.save(rating), qualities);
    }

    @Transactional
    public RatingDto rateCustomer(Long ownerId, Long requestId, RatingInput input) {
        Store store = stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_STORE",
                        "You have not set up your store yet"));
        OrderRequest r = requests.findById(requestId).orElseThrow(RatingService::notFound);
        if (r.getStatus() != RequestStatus.PICKED_UP || r.getAcceptedQuoteId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS",
                    "You can rate only after pickup");
        }
        Quote accepted = quotes.findById(r.getAcceptedQuoteId()).orElseThrow(RatingService::notFound);
        if (!accepted.getStore().getId().equals(store.getId())) {
            throw notFound();
        }
        if (ratings.existsByRequestIdAndRaterId(requestId, ownerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RATED", "You already rated this order");
        }
        User rater = users.findById(ownerId).orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User no longer exists"));
        List<String> qualities = RatingQualities.sanitize(RatingTargetType.CUSTOMER, input.qualities());
        Rating rating = new Rating();
        rating.setRequest(r);
        rating.setRater(rater);
        rating.setTargetType(RatingTargetType.CUSTOMER);
        rating.setCustomer(r.getCustomer());
        rating.setStars(input.stars());
        rating.setQualities(writeQualities(qualities));
        return RatingDto.from(ratings.save(rating), qualities);
    }

    @Transactional(readOnly = true)
    public Map<String, RatingDto> ratingsForRequest(Long requestId, Long viewerId) {
        Map<String, RatingDto> out = new LinkedHashMap<>();
        for (Rating r : ratings.findByRequestId(requestId)) {
            if (!r.getRater().getId().equals(viewerId)
                    && r.getTargetType() == RatingTargetType.CUSTOMER) {
                // Customer privacy: only the shop that was rated-with can see customer rating? 
                // Actually store rates customer — customer can see their own received rating.
                // Viewer is customer seeing store's rating of them, or store seeing customer's rating of store.
            }
            String key = r.getTargetType() == RatingTargetType.STORE ? "store" : "customer";
            // Show own rating always; show the other side's rating to the ratee.
            boolean own = r.getRater().getId().equals(viewerId);
            boolean aboutMe = r.getTargetType() == RatingTargetType.CUSTOMER
                    && r.getCustomer() != null
                    && r.getCustomer().getId().equals(viewerId);
            boolean aboutMyStore = r.getTargetType() == RatingTargetType.STORE
                    && r.getStore() != null
                    && r.getStore().getOwner().getId().equals(viewerId);
            if (own || aboutMe || aboutMyStore) {
                out.put(key, RatingDto.from(r, readQualities(r.getQualities())));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<Long, StoreRatingSummaryDto> summariesForStores(Collection<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoreRatingSummaryDto> out = new HashMap<>();
        for (RatingRepository.StoreRatingAgg row : ratings.aggregateForStores(storeIds, RatingTargetType.STORE)) {
            List<String> top = topQualities(row.getStoreId(), 4);
            double avg = row.getAvgStars() == null ? 0 : Math.round(row.getAvgStars() * 10.0) / 10.0;
            out.put(row.getStoreId(), new StoreRatingSummaryDto(avg, row.getRatingCount(), top));
        }
        return out;
    }

    private List<String> topQualities(Long storeId, int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (String raw : ratings.qualitiesForStore(storeId, RatingTargetType.STORE)) {
            for (String q : readQualities(raw)) {
                counts.merge(q, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String writeQualities(List<String> qualities) {
        try {
            return mapper.writeValueAsString(qualities);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readQualities(String raw) {
        try {
            List<String> list = mapper.readValue(raw == null ? "[]" : raw, LIST_STRING);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
