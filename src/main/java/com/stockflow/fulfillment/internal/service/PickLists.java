package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.fulfillment.internal.domain.ShortPickReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The lines of a pick task - one carton each - reading them, confirming them by scanning the carton's LPN, reporting a
 * carton short, and putting the carton of a cancelled order back (SCRUM-267, 274, 275; team plan 4C-4; kltn-docs 07
 * §4.2 step 5, §4.4, §4.5, BR-04, BR-05, BR-09, BR-10).
 *
 * <p>Inside {@code internal} on purpose, like inventory's {@code StockOperations}: only this module's
 * web layer calls it, and no other module has any business confirming a pick.</p>
 */
public interface PickLists {

    /**
     * A task's lines in walking order. The assignee, and managers of the task's
     * warehouse, may read them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}, {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PickLineView> lines(UUID taskId, int page, int size, CurrentUser actor);

    /**
     * The assignee scanned a line: its location, then its carton's LPN - nothing else is accepted, and no quantity is
     * entered (07 BR-04, BR-10). In the same transaction (in-process, {@code inventory :: consumption}), by the task's
     * destination: a DISPATCH task deducts the line's hold, the whole carton (PICKED); a STAGING task moves the carton
     * to its staging area, still held for the order (STAGED, team plan 4C-5). The same scan again is answered with the
     * line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (task, line, or not the
     *         assignee); {@code OUT_OF_DATA_SCOPE}; {@code PICK_TASK_STATE_CONFLICT};
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code PICK_ITEM_MISMATCH} for a SKU, a
     *         barcode or another carton's LPN; {@code RESERVATION_NOT_ACTIVE}; {@code INVENTORY_POLICY_STOCK_CONFLICT}
     */
    PickLineView confirm(UUID taskId, UUID lineId, String locationCode, String lpn, CurrentUser actor);

    /**
     * Picked and staged lines of cancelled tasks still waiting to go back onto a bin, oldest first; limited to the
     * caller's warehouses and, when given, to one of them. A staged line's carton is in the staging area.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PutBackRow> putBacks(UUID warehouseId, int page, int size, CurrentUser actor);

    /**
     * Puts the carton a line picked back onto a bin of the task's warehouse, whole, confirmed by scanning its LPN (07
     * §4.5 step 14): it is IN_STOCK there again, sealed, AVAILABLE, with one {@code PUT_BACK} ledger line - or, for a
     * line staged and never deducted, one {@code MOVE} from the staging area (team plan 4C-5). Again for the same line
     * and LPN, it answers with the line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PUT_BACK_NOT_REQUIRED}; {@code PICK_ITEM_MISMATCH}; {@code STOCK_PACKAGE_TAKEN_WHOLE} when part
     *         of the carton went to quarantine at packing; {@code PUT_BACK_LOCATION_INVALID}
     */
    PickLineView putBack(UUID taskId, UUID lineId, String locationCode, String lpn, CurrentUser actor);

    /**
     * X-SHORT (07 §4.4 step 9, BR-09): the assignee is at the line's bin and cannot take its carton, for
     * {@code reason}. The line turns SHORT of the whole carton, nothing is deducted, the order goes ON_HOLD with reason
     * {@code INVENTORY} until the coordinator reallocates, and the carton gets a blind {@code SHORT_PICK} count (or the
     * count already holding it). The same report again answers with the line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PICK_TASK_STATE_CONFLICT}; {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}
     */
    PickLineView reportShort(UUID taskId, UUID lineId, String locationCode, ShortPickReason reason, CurrentUser actor);

    /**
     * X-SHORT resolved by reallocation: inventory releases the short allocation and allocates as many whole cartons
     * from other pickable bins of the warehouse (FIFO, never the short bin; 07 §4.4 step 10); the order line holds the new
     * allocations, the short line is superseded by one new line per allocation, and once no line is SHORT any
     * more the order resumes. The warehouse's manager.
     *
     * @return the new lines
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PICK_LINE_NOT_OPEN} unless SHORT; {@code INSUFFICIENT_STOCK} when whole cartons cannot cover it
     *         - and then nothing changes and the order stays ON_HOLD; {@code PICK_HOLD_NOT_IN_CARTON} should an allocation still
     *         land on no carton (a guard), likewise changing nothing
     */
    List<PickLineView> reallocateShort(UUID taskId, UUID lineId, CurrentUser actor);

    /**
     * An approved count wrote off a carton an order still held (kltn-docs 20 §4.2 step 8): inventory gave the hold up
     * and this turns what stood on it into the shortage 07 §4.4 describes, without anyone at the bin. The pick line on
     * the hold turns SHORT ({@code NOT_FOUND}, nothing deducted) and the order is held for stock ({@code INVENTORY}),
     * for the warehouse manager to reallocate. Without a pick line - the order was released and has no task yet - the
     * order is held the same way. Idempotent (the event is delivered at least once): a line already SHORT, a hold
     * already on the order, and a line or order no longer in fulfilment change nothing. Locks the pick, then the order;
     * no stock row.
     *
     * @param decidedBy who approved the count's shortage; the order's hold is recorded in their name
     */
    void shortenForWrittenOffCarton(UUID orderId, UUID reservationId, UUID decidedBy);

    /**
     * A top-up (07 §4.3 step 7, BR-07; team plan 4C-5): the order holds cartons that no line of its task takes yet - the
     * rest of an order that waited for stock, allocated since - and they become lines of the same task, after its last
     * stop, keeping its destination. A STAGED or PICKED task goes back to PICKING for its assignee, and the order to
     * PICKING if it is not (unless it is on hold). Refused once the task's packing is complete: its cartons left with a
     * shipment, and the rest is a new delivery round. The warehouse manager (or the warehouse manager). Nothing new to plan
     * is answered with no lines, changing nothing.
     *
     * @return the lines added
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PICK_TASK_STATE_CONFLICT} when the task is cancelled or its packing complete, or its order is no
     *         longer in fulfilment;
     *         {@code FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE}
     */
    List<PickLineView> topUp(UUID taskId, CurrentUser actor);

    /**
     * One line of a pick list.
     *
     * @param barcode the SKU's barcode, for the picker to read; a scan of it confirms nothing (07 BR-10)
     * @param shortQty the units of a SHORT line (or one reallocated since): the whole carton since 4C-4
     * @param shortReason why the carton could not be taken; null unless reported short since 4C-4
     * @param lpn the carton the line takes, scanned to confirm it and to put it back
     * @param stagedAt when the carton was moved to the staging area (4C-5); null for a line never staged
     * @param stagedLocationCode the staging area it was moved to; null for a line never staged
     */
    record PickLineView(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                        String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                        Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                        int shortQty, ShortPickReason shortReason, String lpn, boolean wholeCarton, Instant stagedAt,
                        String stagedLocationCode) {

        /** A line as it was shown before team plan 4C-5: never staged. */
        public PickLineView(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                            String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                            Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                            int shortQty, ShortPickReason shortReason, String lpn, boolean wholeCarton) {
            this(lineId, taskId, sequenceNo, orderLineId, sku, barcode, locationCode, lotNumber, allocatedQty,
                    pickedQty, status, pickedAt, putBackQty, putBackLocationCode, putBackAt, shortQty, shortReason,
                    lpn, wholeCarton, null, null);
        }
    }

    /**
     * A picked or staged line of a cancelled task, waiting to go back onto a bin.
     *
     * @param lpn       the carton that goes back, scanned to put it back
     * @param fromLocationCode where the carton was picked from - or, for a staged line, the staging area it stands in
     * @param pickedQty what goes back: what the line picked - the whole carton
     */
    /**
     * @param quarantine the check station removed the carton as damaged (4C-6): it goes into a quarantine area, not
     *                   onto a bin
     */
    record PutBackRow(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku, String lotNumber,
                      String lpn, int pickedQty, String fromLocationCode, Instant pickedAt, boolean quarantine) {
        PutBackRow(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku, String lotNumber,
                   String lpn, int pickedQty, String fromLocationCode, Instant pickedAt) {
            this(taskId, orderNumber, warehouseId, lineId, sku, lotNumber, lpn, pickedQty, fromLocationCode, pickedAt,
                    false);
        }
    }
}
