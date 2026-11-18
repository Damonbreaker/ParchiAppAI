package com.kirana.shared;

import java.math.BigDecimal;
import java.time.Instant;

import com.kirana.common.Unit;
import com.kirana.user.User;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "shared_list_items")
@Getter
@Setter
@NoArgsConstructor
public class SharedListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_list_id", nullable = false, updatable = false)
    private SharedList list;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Unit unit;

    @Column(nullable = false)
    private int position;

    @Column(name = "guest_key", length = 64)
    private String guestKey;

    @Column(name = "added_by_label", nullable = false, length = 40)
    private String addedByLabel = "Friend";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by_user_id")
    private User addedByUser;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
