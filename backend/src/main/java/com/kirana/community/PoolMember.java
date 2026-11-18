package com.kirana.community;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.kirana.user.User;

import org.hibernate.annotations.BatchSize;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pool_members")
@Getter
@Setter
@NoArgsConstructor
public class PoolMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pool_id", nullable = false, updatable = false)
    private OrderPool pool;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, length = 40)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PoolMemberStatus status = PoolMemberStatus.JOINED;

    @Column(name = "from_photo", nullable = false)
    private boolean fromPhoto;

    @Column(name = "pickup_code", length = 4)
    private String pickupCode;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    @BatchSize(size = 50)
    private List<PoolMemberItem> items = new ArrayList<>();

    public void addItem(PoolMemberItem item) {
        item.setMember(this);
        items.add(item);
    }

    public void clearItems() {
        items.clear();
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
