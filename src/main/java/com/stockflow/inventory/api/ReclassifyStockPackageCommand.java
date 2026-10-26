package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A QC decision about a whole carton (SCRUM-473): it goes to {@code toLocationCode} and its rows become
 * QUARANTINE or BLOCKED in the same step, keeping the LPN and staying unopened. The carton-sized counterpart of
 * {@link ReclassifyStockCommand}, which would split the carton's row.
 *
 * <p>Only INBOUND or QUARANTINE rows can be reclassified, and only QUARANTINE or BLOCKED is a decision a carton
 * can receive: putaway as AVAILABLE is a plain move of the carton.</p>
 *
 * @param referenceId the document that decided it (a QC inspection)
 * @param reason      why, for the ledger; may be blank
 */
public record ReclassifyStockPackageCommand(
        String lpn,
        String toLocationCode,
        StockDisposition disposition,
        MoveReference reference,
        UUID referenceId,
        UUID actorId,
        String reason
) {

    public ReclassifyStockPackageCommand {
        Objects.requireNonNull(lpn, "lpn");
        Objects.requireNonNull(toLocationCode, "toLocationCode");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceId, "referenceId");
        if (disposition != StockDisposition.QUARANTINE && disposition != StockDisposition.BLOCKED) {
            throw new IllegalArgumentException("A carton is quarantined or blocked, not " + disposition);
        }
    }
}
