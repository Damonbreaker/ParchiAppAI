package com.kirana.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.kirana.user.User;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_requests")
@Getter
@Setter
@NoArgsConstructor
public class OrderRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private User customer;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    /** FK to quotes.id, kept as a plain id (Quote lives in the quote package and points back here). */
    @Column(name = "accepted_quote_id")
    private Long acceptedQuoteId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    /** 4 digits, shown to the customer only; the winning store enters it at handover. */
    @Column(name = "pickup_code", length = 4)
    private String pickupCode;

    /** Wrong pickup codes in a row; reset by a correct code or when a lock expires. */
    @Column(name = "pickup_failed_attempts", nullable = false)
    private int pickupFailedAttempts;

    /** Set after too many wrong codes; no code (even the right one) is accepted until then. */
    @Column(name = "pickup_locked_until")
    private Instant pickupLockedUntil;

    /** Optimistic lock: concurrent changes to the same request fail with 409. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<RequestItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("distanceKm ASC")
    private List<RequestTarget> targets = new ArrayList<>();

    public void addItem(RequestItem item) {
        item.setRequest(this);
        items.add(item);
    }

    public void addTarget(RequestTarget target) {
        target.setRequest(this);
        targets.add(target);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
