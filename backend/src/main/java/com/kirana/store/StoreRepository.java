package com.kirana.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /** Haversine distance in km from (:lat, :lng) to the store row "s". */
    String DISTANCE_KM = """
            (6371.0 * 2 * asin(sqrt(
                power(sin(radians(s.lat - :lat) / 2), 2)
                + cos(radians(:lat)) * cos(radians(s.lat)) * power(sin(radians(s.lng - :lng) / 2), 2))))""";

    String COLUMNS = """
            s.id AS "id", s.name AS "name", s.address AS "address", s.area AS "area",
            s.lat AS "lat", s.lng AS "lng", s.is_open AS "isOpen", """;

    Optional<Store> findByOwnerId(Long ownerId);

    boolean existsByOwnerId(Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Store s where s.id = :id")
    Optional<Store> findByIdForUpdate(@Param("id") Long id);

    /**
     * Open stores within radiusKm, nearest first. The lat/lng box (1 degree of latitude = 111.32 km) only
     * pre-filters, so the partial index ix_stores_open_lat_lng (V5) is used instead of computing the
     * distance for every store; the exact Haversine check still decides.
     */
    @Query(nativeQuery = true, value = "SELECT * FROM (SELECT " + COLUMNS + DISTANCE_KM + """
             AS "distanceKm"
              FROM stores s
             WHERE s.is_open = TRUE
               AND s.lat BETWEEN :lat - :radiusKm / 111.32 AND :lat + :radiusKm / 111.32
               AND s.lng BETWEEN :lng - :radiusKm / (111.32 * greatest(cos(radians(:lat)), 0.01))
                             AND :lng + :radiusKm / (111.32 * greatest(cos(radians(:lat)), 0.01))) t
            WHERE t."distanceKm" <= :radiusKm
            ORDER BY t."distanceKm", t."id"
            LIMIT :limit""")
    List<StoreDistanceRow> findNearestOpen(@Param("lat") double lat, @Param("lng") double lng,
                                           @Param("radiusKm") double radiusKm, @Param("limit") int limit);

    /** Name OR area matches the ILIKE pattern; open and closed; nearest first; no radius. */
    @Query(nativeQuery = true, value = "SELECT " + COLUMNS + DISTANCE_KM + """
             AS "distanceKm"
              FROM stores s
             WHERE s.name ILIKE :pattern OR s.area ILIKE :pattern
             ORDER BY "distanceKm", s.id
             LIMIT :limit""")
    List<StoreDistanceRow> searchByDistance(@Param("pattern") String pattern, @Param("lat") double lat,
                                            @Param("lng") double lng, @Param("limit") int limit);

    /** Same match without a location: sorted by name, distance is null. */
    @Query(nativeQuery = true, value = "SELECT " + COLUMNS + """
             CAST(NULL AS double precision) AS "distanceKm"
              FROM stores s
             WHERE s.name ILIKE :pattern OR s.area ILIKE :pattern
             ORDER BY lower(s.name), s.id
             LIMIT :limit""")
    List<StoreDistanceRow> searchByName(@Param("pattern") String pattern, @Param("limit") int limit);
}
