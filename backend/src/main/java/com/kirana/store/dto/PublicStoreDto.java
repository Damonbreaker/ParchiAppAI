package com.kirana.store.dto;

import java.util.List;

import com.kirana.common.Geo;
import com.kirana.rating.dto.StoreRatingSummaryDto;
import com.kirana.store.Store;
import com.kirana.store.StoreDistanceRow;

/**
 * What customers see. Deliberately has no phone field: contacts are revealed
 * only after a quote is accepted (Phase 5). distanceKm is null when no location was sent.
 */
public record PublicStoreDto(Long id, String name, String address, String area,
                             double lat, double lng, Boolean isOpen, Double distanceKm,
                             Double ratingAvg, Long ratingCount, List<String> topQualities) {

    public static PublicStoreDto from(StoreDistanceRow r, StoreRatingSummaryDto rating) {
        StoreRatingSummaryDto s = rating == null ? StoreRatingSummaryDto.empty() : rating;
        return new PublicStoreDto(r.getId(), r.getName(), r.getAddress(), r.getArea(),
                r.getLat(), r.getLng(), r.getIsOpen(), Geo.round1(r.getDistanceKm()),
                s.avgStars(), s.ratingCount(), s.topQualities());
    }

    public static PublicStoreDto from(Store s, Double distanceKm, StoreRatingSummaryDto rating) {
        StoreRatingSummaryDto x = rating == null ? StoreRatingSummaryDto.empty() : rating;
        return new PublicStoreDto(s.getId(), s.getName(), s.getAddress(), s.getArea(),
                s.getLat(), s.getLng(), s.isOpen(), Geo.round1(distanceKm),
                x.avgStars(), x.ratingCount(), x.topQualities());
    }
}
