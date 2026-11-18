package com.kirana.store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kirana.common.ApiException;
import com.kirana.common.Geo;
import com.kirana.rating.RatingService;
import com.kirana.rating.dto.StoreRatingSummaryDto;
import com.kirana.store.dto.CreateStoreRequest;
import com.kirana.store.dto.MyStoreDto;
import com.kirana.store.dto.PublicStoreDto;
import com.kirana.store.dto.UpdateStoreRequest;
import com.kirana.user.User;
import com.kirana.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    /** Gifted Parchi credits when a shopkeeper creates a store. */
    public static final BigDecimal STARTER_PARCHI = new BigDecimal("100.00");
    /** Fee = 1% of the paid bill (quote total) at pickup. */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.01");

    private final StoreRepository stores;
    private final UserRepository users;
    private final StoreProperties props;
    private final RatingService ratingService;

    // ------------------------------------------------------------ owner side

    @Transactional
    public MyStoreDto createMine(Long ownerId, CreateStoreRequest req) {
        if (stores.existsByOwnerId(ownerId)) {
            throw storeExists();
        }
        User owner = users.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User no longer exists"));

        Store s = new Store();
        s.setOwner(owner);
        s.setName(req.name().trim());
        s.setAddress(req.address().trim());
        s.setArea(req.area().trim());
        s.setPhone(req.phone() != null ? req.phone() : owner.getPhone());
        s.setLat(req.lat());
        s.setLng(req.lng());
        s.setOpen(true);
        s.setParchiBalance(STARTER_PARCHI);
        try {
            s = stores.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw storeExists(); // concurrent create for the same owner
        }
        return MyStoreDto.from(s);
    }

    @Transactional(readOnly = true)
    public MyStoreDto getMine(Long ownerId) {
        return MyStoreDto.from(loadMine(ownerId));
    }

    @Transactional
    public MyStoreDto updateMine(Long ownerId, UpdateStoreRequest req) {
        Store s = loadMine(ownerId);
        if (req.name() != null) s.setName(req.name().trim());
        if (req.address() != null) s.setAddress(req.address().trim());
        if (req.area() != null) s.setArea(req.area().trim());
        if (req.phone() != null) s.setPhone(req.phone());
        if (req.lat() != null) s.setLat(req.lat());
        if (req.lng() != null) s.setLng(req.lng());
        if (req.isOpen() != null) s.setOpen(req.isOpen());
        return MyStoreDto.from(s);
    }

    // ------------------------------------------------------------ customer side

    /** Open stores within the configured radius, nearest first. Phase 3 calls this with limit 5. */
    @Transactional(readOnly = true)
    public List<StoreDistanceRow> findNearestOpen(double lat, double lng, int limit) {
        return stores.findNearestOpen(lat, lng, props.radiusKm(), limit);
    }

    @Transactional(readOnly = true)
    public List<PublicStoreDto> nearby(double lat, double lng) {
        return withRatings(findNearestOpen(lat, lng, props.maxResults()));
    }

    @Transactional(readOnly = true)
    public List<PublicStoreDto> search(String q, Double lat, Double lng) {
        String term = q == null ? "" : q.trim();
        if (term.isEmpty()) {
            if (lat == null || lng == null) {
                Map<String, String> fields = new LinkedHashMap<>();
                if (lat == null) fields.put("lat", "is required when q is empty");
                if (lng == null) fields.put("lng", "is required when q is empty");
                throw ApiException.validation(fields);
            }
            return nearby(lat, lng);
        }
        String pattern = "%" + escapeLike(term) + "%";
        List<StoreDistanceRow> rows = (lat != null && lng != null)
                ? stores.searchByDistance(pattern, lat, lng, props.maxResults())
                : stores.searchByName(pattern, props.maxResults());
        return withRatings(rows);
    }

    @Transactional(readOnly = true)
    public PublicStoreDto getForCustomer(Long storeId, Double lat, Double lng) {
        Store s = stores.findById(storeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found"));
        Double distance = (lat != null && lng != null) ? Geo.haversineKm(lat, lng, s.getLat(), s.getLng()) : null;
        Map<Long, StoreRatingSummaryDto> ratings = ratingService.summariesForStores(List.of(storeId));
        return PublicStoreDto.from(s, distance, ratings.get(storeId));
    }

    private List<PublicStoreDto> withRatings(List<StoreDistanceRow> rows) {
        Map<Long, StoreRatingSummaryDto> ratings = ratingService.summariesForStores(
                rows.stream().map(StoreDistanceRow::getId).toList());
        return rows.stream().map(r -> PublicStoreDto.from(r, ratings.get(r.getId()))).toList();
    }

    /**
     * After pickup/payment: deduct 1% of the bill from the store's Parchi wallet (floor at 0).
     */
    @Transactional
    public BigDecimal deductParchiFee(Long storeId, BigDecimal billTotal) {
        if (billTotal == null || billTotal.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Store s = stores.findByIdForUpdate(storeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Store not found"));
        BigDecimal fee = billTotal.multiply(FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        if (fee.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal bal = s.getParchiBalance() == null ? BigDecimal.ZERO : s.getParchiBalance();
        BigDecimal next = bal.subtract(fee);
        if (next.signum() < 0) {
            next = BigDecimal.ZERO.setScale(2);
        }
        s.setParchiBalance(next);
        stores.saveAndFlush(s);
        return fee;
    }

    /** Demo / shopkeeper top-up: add credits to the wallet. */
    @Transactional
    public MyStoreDto topUpParchi(Long ownerId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.validation(Map.of("amount", "must be greater than 0"));
        }
        Store s = loadMine(ownerId);
        Store locked = stores.findByIdForUpdate(s.getId()).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "NO_STORE", "You have not set up your store yet"));
        BigDecimal bal = locked.getParchiBalance() == null ? BigDecimal.ZERO : locked.getParchiBalance();
        locked.setParchiBalance(bal.add(amount.setScale(2, RoundingMode.HALF_UP)));
        return MyStoreDto.from(stores.saveAndFlush(locked));
    }

    // ------------------------------------------------------------ helpers

    private Store loadMine(Long ownerId) {
        return stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_STORE",
                        "You have not set up your store yet"));
    }

    private static ApiException storeExists() {
        return new ApiException(HttpStatus.CONFLICT, "STORE_EXISTS", "You already have a store");
    }

    /** So that a user typing % or _ searches for that character literally. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
