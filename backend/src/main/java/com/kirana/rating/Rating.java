package com.kirana.rating;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.kirana.request.OrderRequest;
import com.kirana.store.Store;
import com.kirana.user.User;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ratings")
@Getter
@Setter
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private OrderRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rater_id", nullable = false)
    private User rater;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private RatingTargetType targetType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;

    @Column(nullable = false)
    private int stars;

    /** JSON array of quality tag ids, e.g. ["friendly","low_price"]. */
    @Column(nullable = false, length = 1000)
    private String qualities = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
