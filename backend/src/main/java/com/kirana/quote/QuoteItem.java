package com.kirana.quote;

import java.math.BigDecimal;

import com.kirana.common.Unit;
import com.kirana.request.RequestItem;

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

/** The store's answer for one request item. linePrice is the total for the line, not per unit. */
@Entity
@Table(name = "quote_items")
@Getter
@Setter
@NoArgsConstructor
public class QuoteItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private Quote quote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_item_id", nullable = false, updatable = false)
    private RequestItem requestItem;

    @Column(name = "offered_qty", nullable = false, precision = 10, scale = 3)
    private BigDecimal offeredQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Unit unit;

    @Column(name = "line_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal linePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuoteItemStatus status;
}
