package com.stockflow.inventory.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** The append-only stock ledger. Port; the adapter writes {@code inventory.stock_movement}. */
public interface StockMovementLog {

    void append(StockMovement movement);

    /**
     * The cartons a document moved whole, and where to: the carton each of its MOVE lines names, each once, in no
     * particular order; empty when it moved none. How a person's repeated carton move is recognised (kltn-docs 11
     * BR-07): the request id is the reference, and the cartons and the place say whether the repeat is the same
     * request.
     */
    java.util.List<MovedCarton> cartonsMovedBy(StockMovement.ReferenceType referenceType, UUID referenceId);

    /** A carton a document moved, and the location it moved it to. */
    record MovedCarton(StockPackageId carton, LocationId to) {
    }

    /**
     * The line a document already caused, if any — how a retried move is recognised as one that
     * already happened instead of moving the goods twice. When the document caused several (a receipt line
     * received into cartons writes one per carton, SCRUM-473), the first written: ids are UUIDv7, so the
     * lowest is the oldest, and a replay answers what the first call answered.
     */
    Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                            StockMovement.ReferenceType referenceType, UUID referenceId);

    /**
     * Every line of {@code type} a document caused, oldest first; empty when none. A pick line of a staging task writes
     * two MOVE lines - staged out of its bin, then, its order cancelled, put back out of the staging area (team plan
     * 4C-5) - and the put-back is recognised by the second.
     */
    java.util.List<StockMovement> findAllByReference(StockMovement.MovementType type,
                                                     StockMovement.ReferenceType referenceType, UUID referenceId);

    /**
     * Cartons a document's ledger lines of these types name, 0 when it has none: a line is one carton. Several types
     * in one query, because a putaway writes a move, or an in-place status change, under one reference.
     */
    int countByReference(java.util.Collection<StockMovement.MovementType> types,
                         StockMovement.ReferenceType referenceType, UUID referenceId);
}
