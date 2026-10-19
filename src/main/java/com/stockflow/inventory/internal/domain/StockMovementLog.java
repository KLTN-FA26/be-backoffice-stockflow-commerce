package com.stockflow.inventory.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** The append-only stock ledger. Port; the adapter writes {@code inventory.stock_movement}. */
public interface StockMovementLog {

    void append(StockMovement movement);

    /**
     * The line a document already caused, if any — how a retried move is recognised as one that
     * already happened instead of moving the goods twice.
     */
    Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                            StockMovement.ReferenceType referenceType, UUID referenceId);

    /**
     * Whether production handed {@code hold}'s goods over, out of the print shop, to {@code location}: the hand-over
     * line a production move writes ({@code MOVE}, reference {@code PRODUCTION_ORDER}, "Hold &lt;id&gt;",
     * IN_PRODUCTION to AVAILABLE). Tells printed goods in a packing area from free stock somebody moved there.
     */
    boolean handedOverFromProduction(ReservationId hold, LocationId location);
}
