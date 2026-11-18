package com.kirana.rating;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RatingRepository extends JpaRepository<Rating, Long> {

    Optional<Rating> findByRequestIdAndRaterId(Long requestId, Long raterId);

    boolean existsByRequestIdAndRaterId(Long requestId, Long raterId);

    List<Rating> findByRequestId(Long requestId);

    @Query("""
            select r.store.id as storeId, avg(r.stars) as avgStars, count(r) as ratingCount
            from Rating r
            where r.targetType = :target and r.store.id in :storeIds
            group by r.store.id
            """)
    List<StoreRatingAgg> aggregateForStores(
            @Param("storeIds") Collection<Long> storeIds,
            @Param("target") RatingTargetType target);

    @Query("""
            select r.qualities from Rating r
            where r.targetType = :target and r.store.id = :storeId
            """)
    List<String> qualitiesForStore(
            @Param("storeId") Long storeId,
            @Param("target") RatingTargetType target);

    interface StoreRatingAgg {
        Long getStoreId();

        Double getAvgStars();

        Long getRatingCount();
    }
}
