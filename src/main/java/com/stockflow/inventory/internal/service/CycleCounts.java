package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.identity.api.AssignableUser;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cycle counts (SCRUM-146): planned by a manager or planner over a scope of the warehouse, counted on the shelf,
 * submitted so every variance becomes a {@code COUNT_VARIANCE} adjustment through the four-eyes approval of
 * SCRUM-145, and sent for a recount when the manager doubts a figure. The blind recounts a short pick opens (docs
 * 04c X-SHORT step 03) are counted and submitted the same way.
 *
 * <p>The count screen never shows the book quantity to a caller who may not decide counts: on every answer here a
 * blind count carries no expected quantity, no note and no adjustments for them, before or after submission - the
 * variance would give the book quantity away, and a counter who knows what to find finds it. Nor a carton line's
 * counted quantity, and a carton figure is not refused for exceeding the carton's receipt as it is typed (approval
 * refuses it instead): a sealed carton's received quantity is its book quantity. The list carries no lines at all,
 * and no variance tally for them.</p>
 *
 * <p>That is the count screen, not the system: whoever holds {@code READ} on stock items, stock adjustments or the
 * stock ledger still sees quantities there. Restricting those while a blind count is open is an owner decision
 * (final review I4), not something this interface does.</p>
 *
 * <p>Inside {@code internal}: only this module's web layer reads it.</p>
 */
public interface CycleCounts {

    /**
     * Counts, newest first, without their lines: each with how many lines it has and how many are counted, and - for
     * a caller who may decide counts ({@code showExpected}) - how many differ from the books. A blind count hides its
     * note from the others. The lines are read one count at a time ({@link #get}). A warehouse-bound caller sees their
     * own warehouses only.
     *
     * @param status    PLANNED, IN_PROGRESS, COMPLETED or CANCELLED, in any case; null for any
     * @param warehouse one warehouse; null for every warehouse the caller may see
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown status;
     *         {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<CycleCountSummary> list(String status, UUID warehouse, boolean showExpected, int page, int size);

    /**
     * One count with its lines, blinded as {@link #list} does.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}
     */
    CycleCountView get(UUID id, boolean showExpected);

    /**
     * Plans a {@code MANUAL} count over the union of the zones, shelves and bins named, narrowed to the SKUs when
     * any are named; SKUs alone mean every bin (not area) of the warehouse holding them. One line per loose (bin,
     * SKU, lot) with stock and per carton in stock. A slot already on another open count, or whose variance still
     * waits for approval, is left out and reported in {@link PlannedCount#skipped()}, so two counts never correct
     * the same stock. The returned view is blinded as {@link #get} blinds it: planning does not make the planner one
     * who decides, but who decides sees the books of what they planned.
     *
     * @param showExpected the caller may decide counts ({@code inventory-cycle-counts:APPROVE})
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for no criterion, a bin or zone of
     *         another warehouse, a malformed SKU or an assignee who may not count there; {@code WAREHOUSE_NOT_FOUND};
     *         {@code CYCLE_COUNT_EMPTY_SCOPE}; {@code CYCLE_COUNT_TOO_LARGE}; {@code OUT_OF_DATA_SCOPE}
     */
    PlannedCount plan(PlanCycleCountCommand command, boolean showExpected);

    /**
     * Hands a planned count to an active warehouse staff member or manager of its warehouse. The returned view is
     * blinded as {@link #get} blinds it.
     *
     * @param showExpected the caller may decide counts ({@code inventory-cycle-counts:APPROVE})
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code VALIDATION_FAILED};
     *         {@code CONFLICT} once counting started; {@code OUT_OF_DATA_SCOPE}
     */
    CycleCountView assign(UUID id, UUID userId, boolean showExpected);

    /**
     * Records what was found on one line: a quantity, or for an unopened carton whether it is there
     * ({@code present}). The book quantity is re-read at this moment and the variance is judged against it (spec
     * D3). Repeating it before submission overwrites the count. A carton line follows a carton moved whole within
     * the warehouse since planning.
     *
     * @param mayDecide the caller holds {@code inventory-cycle-counts:APPROVE}: counts any line, sees the books
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code CYCLE_COUNT_NOT_OPEN};
     *         {@code CYCLE_COUNT_NOT_ASSIGNEE}; {@code VALIDATION_FAILED} ({@code present} on loose stock or an opened
     *         carton); {@code STOCK_PACKAGE_WRONG_LOCATION} (the carton left the warehouse);
     *         {@code STOCK_PACKAGE_OVERFILLED} (to who decides, or on a count that is not blind);
     *         {@code OUT_OF_DATA_SCOPE}
     */
    CycleCountView count(UUID id, UUID lineId, Integer countedQty, Boolean present, UUID actor, boolean mayDecide);

    /**
     * Adds a slot the plan did not list and the counter found: loose stock of a SKU and lot in a bin that already
     * holds a row of it, or a carton by its LPN scanned in the bin it is recorded at - a LOST carton included, which
     * is how it is found again.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CYCLE_COUNT_UNKNOWN_LAYER} (misplaced goods are a
     *         move, not an adjustment); {@code STOCK_PACKAGE_NOT_FOUND} (also for a carton of another warehouse);
     *         {@code STOCK_PACKAGE_WRONG_LOCATION} (scanned away from its recorded bin);
     *         {@code STOCK_PACKAGE_OVERFILLED}; {@code CYCLE_COUNT_SLOT_BUSY}; and as {@link #count}
     */
    CycleCountView addLine(UUID id, AddCountLineCommand command);

    /**
     * Completes the count and asks for one or more pending {@code COUNT_VARIANCE} adjustments per line whose count
     * differs from the books, in one transaction: a refusal of any line leaves the count open and asks for nothing.
     * Submitting a completed count again returns it unchanged.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CYCLE_COUNT_LINE_NOT_COUNTED};
     *         {@code CYCLE_COUNT_STALE} (stock left the slot after it was counted, or a counted carton was emptied:
     *         count it again);
     *         {@code CYCLE_COUNT_UNKNOWN_LAYER}; {@code CYCLE_COUNT_NOT_OPEN} once cancelled; and as {@link #count}
     */
    CycleCountView submit(UUID id, UUID actor, boolean mayDecide);

    /**
     * The manager doubts a variance and rejected its adjustment: the lines are flagged and a new blind count of just
     * those slots is opened - {@code SHORT_PICK} when the doubted count was one, so the slot stays frozen while it is
     * recounted, {@code MANUAL} otherwise. A line is sent for recount once; a slot to count again after that is
     * planned. Returns the new count, unblinded (only who decides asks).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} for a line not on the count;
     *         {@code CONFLICT} for a line without a rejected adjustment, with a share still pending, or already sent
     *         for recount;
     *         {@code CYCLE_COUNT_NOT_OPEN} for a count not submitted; {@code CYCLE_COUNT_SLOT_BUSY};
     *         {@code CYCLE_COUNT_STALE} for a carton emptied since; {@code OUT_OF_DATA_SCOPE}
     */
    CycleCountView recount(UUID id, List<UUID> lineIds, String note, UUID actor);

    /**
     * Who a count of this warehouse may be handed to: its active warehouse staff and managers.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}
     */
    List<AssignableUser> assignees(UUID warehouseId);

    /**
     * The warehouse manager calls a count off (it is not needed, or the bin was checked by hand), which also lifts
     * the freeze a SHORT_PICK recount puts on the bin, SKU and lot. Cancelling a cancelled count again is a no-op.
     *
     * @param actor who cancelled; logged, since the audit columns are stamped from the security context
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code CYCLE_COUNT_NOT_OPEN} once COMPLETED
     */
    CycleCountView cancel(UUID id, String reason, String actor);

    /**
     * What to count. Location criteria (zones, shelves by their own code such as {@code A01}, full bin codes) are a
     * union; {@code skus} narrows them, or alone selects every bin holding those SKUs.
     *
     * @param plannedDate null for today
     * @param blind       whether a counter is kept from the book quantities
     */
    record PlanCycleCountCommand(UUID warehouseId, LocalDate plannedDate, UUID assignedUserId, boolean blind,
                                 String note, List<UUID> zoneIds, List<String> shelfCodes, List<String> locationCodes,
                                 List<String> skus) {

        public PlanCycleCountCommand {
            zoneIds = zoneIds == null ? List.of() : List.copyOf(zoneIds);
            shelfCodes = shelfCodes == null ? List.of() : List.copyOf(shelfCodes);
            locationCodes = locationCodes == null ? List.of() : List.copyOf(locationCodes);
            skus = skus == null ? List.of() : List.copyOf(skus);
        }
    }

    /**
     * A found slot, always with {@code locationCode}, the bin it was found in: with {@code lpn} for a carton, which
     * must be recorded at that bin, or with {@code sku} (and the lot) for loose stock.
     */
    record AddCountLineCommand(String locationCode, String sku, String lotNumber, String lpn, int countedQty,
                               UUID actor, boolean mayDecide) { }

    /** The planned count and the slots left out because another count holds them. */
    record PlannedCount(CycleCountView count, List<SkippedSlot> skipped) {

        public PlannedCount {
            skipped = List.copyOf(skipped);
        }
    }

    /** A slot left out of a plan, with the number of the count holding it; {@code lpn} null for loose stock. */
    record SkippedSlot(String locationCode, String sku, String lotNumber, String lpn, String countNumber) { }
}
