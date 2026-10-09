package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link StockAdjustment}. Listing lives on the search side, not here. */
public interface StockAdjustmentRepository {

    Optional<StockAdjustment> findById(UUID id);

    /** {@code ADJ-yyyyMMdd-0001}, from a per-day counter that never hands out the same value twice. */
    String nextNumber(LocalDate day);

    StockAdjustment save(StockAdjustment adjustment);
}
