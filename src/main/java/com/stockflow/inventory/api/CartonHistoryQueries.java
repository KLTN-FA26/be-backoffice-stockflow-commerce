package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One carton, its history on the stock ledger and the open work that names it, for a person looking for it (the
 * back-office assistant's carton investigation, ADR-0011): a short pick, a count line found missing, a carton nobody
 * can find. Each fact is the one a screen already shows - the carton lookup ({@code GET /stock-packages/{lpn}}), its
 * history ({@code GET /movements?lpn=}), the count and adjustment queues - read in one call so the person does not open
 * four screens, and nothing more.
 *
 * <p><b>Scope and blind count.</b> Call it inside the guard of the carton lookup,
 * {@code @RequiresPermission(resource = "inventory-stock-packages", action = READ)}: a carton of a warehouse the caller
 * is not assigned to is refused as that screen refuses it. A caller who may not see the books
 * ({@code BookVisibility}, kltn-docs 20 BR-07) gets the history as the ledger screen gives it to them - without the
 * lines of a carton a count holds - and no pending adjustment of a carton a count holds, whose direction would tell the
 * counter what the books say; {@link CartonHistory#booksHidden} says so, so the reader is told why the history is
 * empty rather than shown nothing as if nothing happened. The blind count covers those two - the ledger and the
 * adjustments - and nothing else: the carton's status, stock status and location are given as the carton lookup gives
 * them to the same counter, since finding the carton is what the count asks of them.</p>
 *
 * <p>Whether the caller may read the ledger, the adjustments or the pick tasks is the caller's to decide, as each of
 * those screens has its own permission: this read gives every fact and names its kind.</p>
 */
public interface CartonHistoryQueries {

    /** The most ledger lines one call returns. */
    int MAX_LINES = 50;

    /**
     * @param lpn   the carton's label, any case
     * @param lines ledger lines wanted, newest first, 1..{@value #MAX_LINES}
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}
     *         for a carton in a warehouse the caller is not assigned to; {@code VALIDATION_FAILED} for {@code lines}
     *         out of range
     */
    CartonHistory history(String lpn, int lines);

    /**
     * @param status        RECEIVING, IN_STOCK, CONSUMED, LOST or VOID
     * @param stockStatus   the stock status while it is in stock (AVAILABLE, INBOUND, QUARANTINE...); null otherwise
     * @param locationCode  where the books last put it; a CONSUMED carton keeps where it was emptied
     * @param warehouseId   the warehouse of {@code locationCode}; null when it is not known
     * @param booksHidden   a count holds the carton and the caller may not see the books: the ledger lines of it and
     *                      its pending adjustments are left out (the carton's own status and location are not)
     * @param ledger        newest first, at most the lines asked for
     * @param ledgerTotal   every line the caller may see; above {@code ledger}'s size, older ones were left out
     * @param openWork      the open work naming the carton, each kind at most a few rows
     */
    record CartonHistory(UUID stockPackageId, String lpn, String sku, String status, String stockStatus,
                         String locationCode, UUID warehouseId, String warehouseCode, String lotNumber,
                         Instant receivedAt, boolean booksHidden, List<LedgerLine> ledger, long ledgerTotal,
                         List<OpenWork> openWork) {

        public CartonHistory {
            ledger = List.copyOf(ledger);
            openWork = List.copyOf(openWork);
        }
    }

    /**
     * One line of the carton's history: the whole carton moved, arrived or left.
     *
     * @param movementType  the ledger's type (RECEIPT, PUTAWAY, MOVE, PICK, ADJUSTMENT...)
     * @param referenceType what the line was written for (PICK_LINE, GOODS_RECEIPT...); null for none
     * @param actorId       who did it; null for the system
     */
    record LedgerLine(Instant at, String movementType, String fromLocation, String toLocation, String referenceType,
                      UUID referenceId, UUID actorId) {
    }

    /** What kind of open work names the carton. */
    enum WorkKind {
        /** A putaway task not done; {@code id} is the task. */
        PUTAWAY_TASK,
        /** A pick task still to pick the carton, or holding it in staging; {@code id} is the pick task. */
        PICK_TASK,
        /** A carton check not completed; {@code id} is the pick task it belongs to. */
        CARTON_CHECK,
        /** An open count with a line for the carton; {@code id} is the count. */
        COUNT,
        /** An adjustment of the carton waiting for approval; {@code id} is the adjustment. */
        ADJUSTMENT
    }

    /**
     * @param number the document number where the work has one (a count's, an adjustment's); null otherwise
     * @param status its status, in its module's words; null where the source does not say
     */
    record OpenWork(WorkKind kind, UUID id, String number, String status) {
    }
}
