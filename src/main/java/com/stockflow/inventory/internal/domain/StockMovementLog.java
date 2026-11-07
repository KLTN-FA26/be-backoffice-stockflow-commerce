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
     * {@link #findLooseByReference} for many documents at once: for each reference id that has a loose line, its
     * first (lowest id); one query. A reference with none is absent.
     */
    java.util.Map<UUID, StockMovement> findLooseByReferences(StockMovement.MovementType type,
                                                             StockMovement.ReferenceType referenceType,
                                                             java.util.Collection<UUID> referenceIds);

    /**
     * Units a document's ledger lines of these types add up to, 0 when it has none: how much of a putaway task has
     * been put away, counting every part, loose or in a carton. Several types in one query, because a putaway writes
     * both moves and in-place status changes under one reference. A document booked in several steps - a return line
     * whose goods came back in more than one parcel - recognises a retried step by the total it already reached.
     */
    int sumQuantityByReference(java.util.Collection<StockMovement.MovementType> types,
                               StockMovement.ReferenceType referenceType, UUID referenceId);
}
