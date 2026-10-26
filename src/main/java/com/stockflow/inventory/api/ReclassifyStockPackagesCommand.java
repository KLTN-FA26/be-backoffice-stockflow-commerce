package com.stockflow.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A QC decision about whole cartons (SCRUM-473): they go to {@code toLocationCode} and their rows become
 * QUARANTINE or BLOCKED in the same step, each keeping its LPN and staying unopened. The carton-sized counterpart of
 * {@link ReclassifyStockCommand}, which would split a carton's row. Several at once for the reason
 * {@link MoveStockPackagesCommand} gives.
 *
 * <p>Only INBOUND or QUARANTINE rows can be reclassified, and only QUARANTINE or BLOCKED is a decision a carton
 * can receive: putaway as AVAILABLE is a plain move of the carton.</p>
 *
 * @param lpns        the cartons, in either case; a repeat is the same carton
 * @param referenceId the document that decided it (a QC inspection)
 * @param reason      why, for the ledger; may be blank
 */
public record ReclassifyStockPackagesCommand(
        List<String> lpns,
        String toLocationCode,
        StockDisposition disposition,
        MoveReference reference,
        UUID referenceId,
        UUID actorId,
        String reason
) {

    public ReclassifyStockPackagesCommand {
        lpns = StockPackageView.normalLpns(lpns);
        Objects.requireNonNull(toLocationCode, "toLocationCode");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceId, "referenceId");
        if (disposition != StockDisposition.QUARANTINE && disposition != StockDisposition.BLOCKED) {
            throw new IllegalArgumentException("A carton is quarantined or blocked, not " + disposition);
        }
    }

    /** One carton. */
    public ReclassifyStockPackagesCommand(String lpn, String toLocationCode, StockDisposition disposition,
                                          MoveReference reference, UUID referenceId, UUID actorId, String reason) {
        this(java.util.Collections.singletonList(lpn), toLocationCode, disposition, reference, referenceId, actorId,
                reason);
    }
}
