package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Move a labelled carton whole to another location of its warehouse (SCRUM-473): the carton, its stock rows and
 * their holds go together, and the carton stays unopened. The carton is named by its LPN because a move named by
 * (SKU, location, lot) cannot tell one carton of a receipt from another.
 *
 * @param lpn           the carton's licence plate
 * @param toLocationCode where the carton goes; another location of the same warehouse
 * @param reference     what caused the move
 * @param referenceId   the id of that document
 * @param actorId       who moved it, for the ledger; null for a system move
 */
public record MoveStockPackageCommand(
        String lpn,
        String toLocationCode,
        MoveReference reference,
        UUID referenceId,
        UUID actorId
) {

    public MoveStockPackageCommand {
        lpn = Objects.requireNonNull(StockPackageView.normalLpn(lpn), "lpn");
        Objects.requireNonNull(toLocationCode, "toLocationCode");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceId, "referenceId");
    }
}
