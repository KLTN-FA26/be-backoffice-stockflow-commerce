package com.stockflow.inventory.internal.domain;

/** One (location, status) subtotal of a SKU; the input the warehouse-level roll-up sums. */
public record StockLevelLine(LocationId location, StockStatus status, int onHand, int reserved, java.time.LocalDate expiryDate) {
    public StockLevelLine(LocationId location, StockStatus status, int onHand, int reserved) {
        this(location,status,onHand,reserved,null);
    }
}
