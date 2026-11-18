package com.kirana.community;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.kirana.request.OrderRequest;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_pools")
@Getter
@Setter
@NoArgsConstructor
public class OrderPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 24)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false, updatable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false, updatable = false)
    private User host;

    @Column(nullable = false, length = 80)
    private String title = "Tower delivery pool";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PoolStatus status = PoolStatus.OPEN;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes = 12;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_request_id")
    private OrderRequest orderRequest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "pool", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<PoolMember> members = new ArrayList<>();

    public void addMember(PoolMember member) {
        member.setPool(this);
        members.add(member);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (opensAt == null) {
            opensAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
