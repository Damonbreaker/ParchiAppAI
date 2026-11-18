package com.kirana.request;

import java.math.BigDecimal;
import java.time.Instant;

import com.kirana.store.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per store that received a request: 5 (or fewer) for NEAREST, 1 for DIRECT. */
@Entity
@Table(name = "request_target")
@Getter
@Setter
@NoArgsConstructor
public class RequestTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private OrderRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "distance_km", nullable = false, precision = 6, scale = 2)
    private BigDecimal distanceKm;

    /** Set the first time the store owner opens the request. */
    @Column(name = "seen_at")
    private Instant seenAt;
}
