package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.UUID;

/**
 * The lines of a pick task: reading them, confirming them by scan, and putting the goods of a cancelled
 * order back (SCRUM-267, 274, 275; kltn-docs 07 step 6, BR-04, BR-05).
 *
 * <p>Inside {@code internal} on purpose, like inventory's {@code StockOperations}: only this module's
 * web layer calls it, and no other module has any business confirming a pick.</p>
 */
public interface PickLists {

    /**
     * A task's lines in walking order. The assignee, and managers, QC and coordinators of the task's
     * warehouse, may read them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}, {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PickLineView> lines(UUID taskId, int page, int size, CurrentUser actor);

    /**
     * The assignee scanned a line: its location, its SKU or barcode, the quantity. Deducts the line's
     * hold in the same transaction (in-process, {@code inventory :: consumption}). The same scan again is
     * answered with the line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (task, line, or not the
     *         assignee); {@code OUT_OF_DATA_SCOPE}; {@code PICK_TASK_STATE_CONFLICT};
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code PICK_ITEM_MISMATCH};
     *         {@code PICK_QUANTITY_EXCEEDS_ALLOCATION}; {@code PICK_QUANTITY_SHORT};
     *         {@code RESERVATION_NOT_ACTIVE}; {@code INVENTORY_POLICY_STOCK_CONFLICT}
     */
    PickLineView confirm(UUID taskId, UUID lineId, String locationCode, String scannedCode, int quantity,
                         CurrentUser actor);

    /**
     * Picked lines of cancelled tasks still waiting to go back onto a bin, oldest first; limited to the
     * caller's warehouses and, when given, to one of them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PutBackRow> putBacks(UUID warehouseId, int page, int size, CurrentUser actor);

    /**
     * Puts everything a line picked back onto a bin of the task's warehouse. Again for the same line, it
     * answers with the line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PUT_BACK_NOT_REQUIRED}; {@code PUT_BACK_LOCATION_INVALID}
     */
    PickLineView putBack(UUID taskId, UUID lineId, String locationCode, CurrentUser actor);

    /**
     * One line of a pick list.
     *
     * @param barcode the SKU's barcode, which a scan may send instead of the SKU; null when it has none
     */
    record PickLineView(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                        String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                        Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt) { }

    /** A picked line of a cancelled task, waiting to go back onto a bin. */
    record PutBackRow(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku, String lotNumber,
                      int pickedQty, String fromLocationCode, Instant pickedAt) { }
}
