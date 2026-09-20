package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockStatus;

/**
 * Flat (location, status) subtotal, built by a JPQL constructor expression so nothing enters the
 * persistence context. {@code sum()} yields a {@code Long}, hence the widened parameters.
 */
public record StockLevelRow(String locationCode, StockStatus status, long onHand, long reserved) {

    StockLevelLine toLine() {
        return new StockLevelLine(new LocationId(locationCode), status, (int) onHand, (int) reserved);
    }
}
