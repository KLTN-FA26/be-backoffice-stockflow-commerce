package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.identity.api.AssignableUser;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cycle counts by carton (SCRUM-146; kltn-docs 20 §4.2, team plan 4C-3): planned by a manager over a scope of the
 * warehouse - one line per LPN carton in stock - each carton scanned present or not found on the shelf, submitted so
 * every carton found against the books becomes a {@code COUNT_VARIANCE} adjustment of that whole carton through the
 * four-eyes approval of SCRUM-145, and sent for a recount when the manager doubts a line. The blind recounts a short
 * pick opens (docs 04c X-SHORT step 03) are counted and submitted the same way.
 *
 * <p>The count screen never shows the books to a caller who may not decide counts (BR-07): on every answer here a
 * blind count carries no booked state, no note and no adjustments for them, before or after submission - the variance
 * would give the books away, and a counter who knows what to find finds it. What the counter scanned (found or not)
 * is theirs to see. The list carries no lines at all, and no variance tally for them.</p>
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
     * any are named; SKUs alone mean every bin (not area) of the warehouse holding them. One line per carton in stock
     * (20 §4.2 a2), at most 500 (BR-12). A carton already on another open count, or whose variance still waits for
     * approval, is left out and reported in {@link PlannedCount#skipped()}, so two counts never correct the same
     * carton (BR-10). The returned view is blinded as {@link #get} blinds it: planning does not make the planner one
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
     * Records whether the line's carton was found at its bin ({@code found}: scanned) or not (20 §4.2 b4, BR-14: never
     * a number of units). Whether the books have it there is read at this moment, and the variance is judged against
     * that (BR-09). Repeating it before submission overwrites the answer. The line follows a carton moved whole within
     * the warehouse since planning (§4.2 b6).
     *
     * @param mayDecide the caller holds {@code inventory-cycle-counts:APPROVE}: counts any line, sees the books
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code CYCLE_COUNT_NOT_OPEN};
     *         {@code CYCLE_COUNT_NOT_ASSIGNEE}; {@code CYCLE_COUNT_LINE_COUNTED_BY_OTHER};
     *         {@code STOCK_PACKAGE_WRONG_LOCATION} (the carton left the warehouse); {@code CONFLICT} (found, but the
     *         carton was emptied or voided); {@code OUT_OF_DATA_SCOPE}
     */
    CycleCountView count(UUID id, UUID lineId, boolean found, UUID actor, boolean mayDecide);

    /**
     * Adds a carton the plan did not list, scanned by its LPN in the bin it is recorded at (20 §4.2 b5) - a LOST
     * carton included, which is how it is found again (a surplus).
     *
     * @throws com.stockflow.common.error.BusinessException {@code STOCK_PACKAGE_NOT_FOUND} (also for a carton of
     *         another warehouse); {@code STOCK_PACKAGE_WRONG_LOCATION} (scanned away from its recorded bin: a move puts
     *         it right, not an adjustment); {@code CONFLICT} (a carton not in stock nor LOST);
     *         {@code CYCLE_COUNT_SLOT_BUSY}; {@code CYCLE_COUNT_TOO_LARGE} (the count already holds 500 lines,
     *         BR-12); and as {@link #count}
     */
    CycleCountView addLine(UUID id, AddCountLineCommand command);

    /**
     * Completes the count and asks for one pending {@code COUNT_VARIANCE} adjustment per carton found against the
     * books - a carton missing, or a LOST one found - in one transaction (20 §4.2 c8): a refusal of any line leaves
     * the count open and asks for nothing. Submitting a completed count again returns it unchanged.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CYCLE_COUNT_LINE_NOT_COUNTED};
     *         {@code CYCLE_COUNT_STALE} (BR-09: the carton of any counted line moved, emptied, written off, lost or
     *         found after the line was counted: count it again); {@code CYCLE_COUNT_NOT_OPEN} once cancelled; and as
     *         {@link #count}
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
     *         or a loose line of the history; {@code CYCLE_COUNT_NOT_OPEN} for a count not submitted;
     *         {@code CYCLE_COUNT_SLOT_BUSY}; {@code CYCLE_COUNT_STALE} for a carton emptied since;
     *         {@code OUT_OF_DATA_SCOPE}
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

    /** A carton found: the bin it was found in ({@code locationCode}) and its {@code lpn}, scanned. */
    record AddCountLineCommand(String locationCode, String lpn, UUID actor, boolean mayDecide) { }

    /** The planned count and the slots left out because another count holds them. */
    record PlannedCount(CycleCountView count, List<SkippedSlot> skipped) {

        public PlannedCount {
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * A carton left out of a plan, with the number of the document holding it: the count, or the adjustment asked by
     * hand that still waits for approval.
     */
    record SkippedSlot(String locationCode, String sku, String lotNumber, String lpn, String countNumber) { }
}
