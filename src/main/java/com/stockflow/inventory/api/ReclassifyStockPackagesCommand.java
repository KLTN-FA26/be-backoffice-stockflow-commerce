package com.stockflow.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A decision about whole cartons (SCRUM-473, SCRUM-92): they go to {@code toLocationCode} and their rows become
 * AVAILABLE (putaway), QUARANTINE or BLOCKED (QC) in the same step, each keeping its LPN and staying unopened. The
 * carton-sized counterpart of
 * {@link ReclassifyStockCommand}, which would split a carton's row. Several at once for the reason
 * {@link MoveStockPackagesCommand} gives.
 *
 * <p>Only INBOUND or QUARANTINE rows can be reclassified; AVAILABLE is putaway and takes INBOUND rows only. The
 * status change travels with the move to the bin: a plain move would leave the row INBOUND, which is not on sale.
 * AVAILABLE is refused under any reference but {@link MoveReference#PUTAWAY_TASK}: the bin rules (a bin, its storage
 * class, putaway target, capacity) are checked by the putaway, not here, so any other caller would make goods
 * sellable wherever it names.</p>
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
        if (disposition != StockDisposition.AVAILABLE && disposition != StockDisposition.QUARANTINE
                && disposition != StockDisposition.BLOCKED) {
            throw new IllegalArgumentException("A carton is put away, quarantined or blocked, not " + disposition);
        }
        if (disposition == StockDisposition.AVAILABLE && reference != MoveReference.PUTAWAY_TASK) {
            throw new IllegalArgumentException("A carton becomes AVAILABLE only through a putaway task, not " + reference);
        }
    }

    /** One carton. */
    public ReclassifyStockPackagesCommand(String lpn, String toLocationCode, StockDisposition disposition,
                                          MoveReference reference, UUID referenceId, UUID actorId, String reason) {
        this(java.util.Collections.singletonList(lpn), toLocationCode, disposition, reference, referenceId, actorId,
                reason);
    }
}
