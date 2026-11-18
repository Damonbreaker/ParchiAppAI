package com.kirana.request;

import java.math.BigDecimal;

import com.kirana.common.Unit;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "request_items")
@Getter
@Setter
@NoArgsConstructor
public class RequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private OrderRequest request;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Unit unit;

    /** 1-based order in the customer's list. */
    @Column(nullable = false)
    private int position;
}
