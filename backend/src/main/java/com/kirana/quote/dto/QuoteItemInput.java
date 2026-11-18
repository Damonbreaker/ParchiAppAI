package com.kirana.quote.dto;

import java.math.BigDecimal;

import com.kirana.common.Unit;
import com.kirana.quote.QuoteItemStatus;

import jakarta.validation.constraints.NotNull;

/**
 * One line of a quote. offeredQty, unit and linePrice are checked in QuoteService because their rules
 * depend on status: required and > 0 for AVAILABLE/PARTIAL, ignored (stored as 0) for UNAVAILABLE.
 */
public record QuoteItemInput(@NotNull Long requestItemId, BigDecimal offeredQty, Unit unit, BigDecimal linePrice,
                             @NotNull QuoteItemStatus status) {
}
