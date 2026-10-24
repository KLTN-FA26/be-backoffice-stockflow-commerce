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
     * The units a document's lines moved so far: a document booked in several steps - a return line whose goods came
     * back in more than one parcel - recognises a retried step by the total it already reached.
     */
    long quantityByReference(StockMovement.MovementType type, StockMovement.ReferenceType referenceType,
                             UUID referenceId);

    /**
     * Whether production handed {@code hold}'s goods over, out of the print shop, to {@code location}: the hand-over
     * line a production move writes ({@code MOVE}, reference {@code PRODUCTION_ORDER}, "Hold &lt;id&gt;",
     * IN_PRODUCTION to AVAILABLE). Tells printed goods in a packing area from free stock somebody moved there.
     */
    boolean handedOverFromProduction(ReservationId hold, LocationId location);

    /**
     * Decision G4-Q2 (2026-10-21): whether {@code hold}'s goods left stock at {@code location} because their production
     * order completed: a {@code CONSUMPTION} line, reference {@code PRODUCTION_ORDER}, "Hold &lt;id&gt;", from that
     * location. Tells printed goods consumed for their order from
     * a hold a pick or anything else closed.
     */
    boolean consumedAtCompletion(ReservationId hold, LocationId location);

    /**
     * Whether {@code hold} was opened to bring a cancelled order's consumed printed goods back onto {@code location}
     * for their SCRAP write-off ({@code PUT_BACK}, reference {@code ORDER}, "Hold &lt;id&gt;", to that location).
     */
    boolean bookedBackForScrap(ReservationId hold, LocationId location);
}
