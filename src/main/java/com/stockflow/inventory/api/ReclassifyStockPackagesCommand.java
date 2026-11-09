package com.stockflow.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A decision about whole cartons (SCRUM-473, SCRUM-92): they go to {@code toLocationCode} and their rows become
 * AVAILABLE (putaway), QUARANTINE or BLOCKED in the same step, each keeping its LPN and staying unopened. The
 * carton-sized counterpart of
 * {@link ReclassifyStockCommand}, which would split a carton's row. Several at once for the reason
 * {@link MoveStockPackagesCommand} gives.
 *
 * <p>Only INBOUND or QUARANTINE rows can be reclassified. AVAILABLE is putaway and takes INBOUND rows - or, for the
 * putaway of a carton a warehouse manager released from quarantine ({@code quarantineRelease}, kltn-docs 05 §4.4,
 * BR-14; team plan 4D-1), QUARANTINE rows and nothing else. The status change travels with the move to the bin: a
 * plain move would leave the row INBOUND, which is not on sale. AVAILABLE is refused under any reference but
 * {@link MoveReference#PUTAWAY_TASK}: the bin rules (a bin, its storage class, putaway target, capacity) are checked
 * by the putaway, not here, so any other caller would make goods sellable wherever it names.</p>
 *
 * @param lpns              the cartons, in either case; a repeat is the same carton
 * @param referenceId       the document that decided it (a putaway task)
 * @param reason            why, for the ledger; may be blank
 * @param quarantineRelease the putaway of a released carton: its rows must be QUARANTINE, where a receipt's must be
 *                          INBOUND; only with AVAILABLE
 */
public record ReclassifyStockPackagesCommand(
        List<String> lpns,
        String toLocationCode,
        StockDisposition disposition,
        MoveReference reference,
        UUID referenceId,
        UUID actorId,
        String reason,
        boolean quarantineRelease
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
        if (quarantineRelease && disposition != StockDisposition.AVAILABLE) {
            throw new IllegalArgumentException("A released carton is put away as AVAILABLE, not " + disposition);
        }
    }

    /** Cartons of a receipt, or a decision that is not a putaway. */
    public ReclassifyStockPackagesCommand(List<String> lpns, String toLocationCode, StockDisposition disposition,
                                          MoveReference reference, UUID referenceId, UUID actorId, String reason) {
        this(lpns, toLocationCode, disposition, reference, referenceId, actorId, reason, false);
    }

    /** One carton. */
    public ReclassifyStockPackagesCommand(String lpn, String toLocationCode, StockDisposition disposition,
                                          MoveReference reference, UUID referenceId, UUID actorId, String reason) {
        this(java.util.Collections.singletonList(lpn), toLocationCode, disposition, reference, referenceId, actorId,
                reason, false);
    }

    /** One carton; {@code quarantineRelease} as above. */
    public ReclassifyStockPackagesCommand(String lpn, String toLocationCode, StockDisposition disposition,
                                          MoveReference reference, UUID referenceId, UUID actorId, String reason,
                                          boolean quarantineRelease) {
        this(java.util.Collections.singletonList(lpn), toLocationCode, disposition, reference, referenceId, actorId,
                reason, quarantineRelease);
    }
}
