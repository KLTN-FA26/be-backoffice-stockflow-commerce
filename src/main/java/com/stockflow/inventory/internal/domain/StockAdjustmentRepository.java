package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link StockAdjustment}. Listing lives on the search side, not here. */
public interface StockAdjustmentRepository {

    Optional<StockAdjustment> findById(UUID id);

    /**
     * The adjustment with its row locked until the transaction ends ({@code SELECT ... FOR UPDATE}) and read fresh:
     * what a decision - approve, reject, withdraw - works on, so two decisions of one adjustment are taken one after
     * the other and the second finds it decided (WF-05 fix plan, section 8). Taken after the SKU advisory lock where
     * the decision also touches stock.
     */
    Optional<StockAdjustment> findByIdForUpdate(UUID id);

    /** {@code ADJ-yyyyMMdd-0001}, from a per-day counter that never hands out the same value twice. */
    String nextNumber(LocalDate day);

    StockAdjustment save(StockAdjustment adjustment);

    /** The latest write-off ever asked for {@code reservationId}'s units, whatever became of it. */
    Optional<StockAdjustment> findLatestForHold(UUID reservationId);

    /**
     * The adjustment of this carton still waiting for approval, if any: one at a time
     * ({@code uk_stock_adjustment_pending_package}), so a carton is never decided twice.
     */
    Optional<StockAdjustment> findPendingForPackage(StockPackageId stockPackageId);

    /** The write-off of {@code reservationId}'s units that still stands: pending or posted. */
    Optional<StockAdjustment> findLiveForHold(UUID reservationId);
}
