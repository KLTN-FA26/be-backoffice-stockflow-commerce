package com.stockflow.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Move labelled cartons whole to another location of their warehouse (SCRUM-473): each carton, its stock rows and
 * their holds go together, and the carton stays unopened. Cartons are named by LPN because a move named by
 * (SKU, location, lot) cannot tell one carton of a receipt from another.
 *
 * <p>Several at once because a document moves a line's cartons together (a receipt line to QC): one call takes the
 * locks once for all of them, where a call per carton read, locked and wrote the same things hundreds of times.</p>
 *
 * @param lpns           the cartons' licence plates, in either case ({@link StockPackageView#normalLpn}); a repeat
 *                       is the same carton
 * @param toLocationCode where the cartons go; another location of the same warehouse
 * @param reference      what caused the move
 * @param referenceId    the id of that document
 * @param actorId        who moved them, for the ledger; null for a system move
 */
public record MoveStockPackagesCommand(
        List<String> lpns,
        String toLocationCode,
        MoveReference reference,
        UUID referenceId,
        UUID actorId
) {

    public MoveStockPackagesCommand {
        lpns = StockPackageView.normalLpns(lpns);
        Objects.requireNonNull(toLocationCode, "toLocationCode");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceId, "referenceId");
    }

    /** One carton. */
    public MoveStockPackagesCommand(String lpn, String toLocationCode, MoveReference reference, UUID referenceId,
                                    UUID actorId) {
        this(java.util.Collections.singletonList(lpn), toLocationCode, reference, referenceId, actorId);
    }
}
