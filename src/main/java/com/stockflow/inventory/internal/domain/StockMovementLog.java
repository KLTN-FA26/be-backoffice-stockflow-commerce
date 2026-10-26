package com.stockflow.inventory.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** The append-only stock ledger. Port; the adapter writes {@code inventory.stock_movement}. */
public interface StockMovementLog {

    void append(StockMovement movement);

    /**
     * The line a document already caused, if any — how a retried move is recognised as one that
     * already happened instead of moving the goods twice. When the document caused several (a receipt line
     * received into cartons writes one per carton, SCRUM-473), the first written: ids are UUIDv7, so the
     * lowest is the oldest, and a replay answers what the first call answered.
     */
    Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                            StockMovement.ReferenceType referenceType, UUID referenceId);

    /**
     * {@link #findByReference}, among the lines that name no carton on either side: how a retried loose move is
     * recognised. A document can move a carton whole and loose units under one reference (SCRUM-473: a receipt line
     * to QC, a QC part to quarantine); a lookup that also saw the carton's lines would take them for the loose
     * move's first attempt and skip it, so whichever ran first would decide whether the loose units moved at all.
     */
    Optional<StockMovement> findLooseByReference(StockMovement.MovementType type,
                                                 StockMovement.ReferenceType referenceType, UUID referenceId);

    /**
     * Whether production handed {@code hold}'s goods over, out of the print shop, to {@code location}: the hand-over
     * line a production move writes ({@code MOVE}, reference {@code PRODUCTION_ORDER}, "Hold &lt;id&gt;",
     * IN_PRODUCTION to AVAILABLE). Tells printed goods in a packing area from free stock somebody moved there.
     */
    /**
     * Units a document's ledger lines add up to, 0 when it has none: how much of a putaway task has been put away,
     * counting every part, loose or in a carton.
     */
    int sumQuantityByReference(StockMovement.MovementType type, StockMovement.ReferenceType referenceType,
                               UUID referenceId);

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
