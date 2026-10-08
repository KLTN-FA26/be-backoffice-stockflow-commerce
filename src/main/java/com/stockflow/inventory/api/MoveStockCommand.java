package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * Move unreserved units of one SKU and lot from one location to another (SCRUM-424): issuing blanks
 * to the {@code PRODUCTION} area, moving printed goods to {@code PACKING}, a putaway, a re-slot.
 *
 * @param requestId     the caller's id for this move. A retry with the same id returns the move
 *                      already made instead of moving the goods twice
 * @param lotNumber     null for a SKU that is not lot-tracked
 * @param reference     what caused the move; {@link MoveReference#MOVE_TASK} for a move a person
 *                      asks for directly
 * @param referenceId   the id of that document; null means the request id itself
 * @param actorId       who moved the goods, for the ledger; null for a system move
 */
public record MoveStockCommand(
        UUID requestId,
        Sku sku,
        String lotNumber,
        String fromLocation,
        String toLocation,
        int quantity,
        MoveReference reference,
        UUID referenceId,
        UUID actorId
) {

    public MoveStockCommand {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(fromLocation, "fromLocation");
        Objects.requireNonNull(toLocation, "toLocation");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A move needs a positive quantity");
        }
        reference = reference == null ? MoveReference.MOVE_TASK : reference;
    }
}
