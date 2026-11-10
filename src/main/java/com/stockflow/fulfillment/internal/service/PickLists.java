package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.fulfillment.internal.domain.ShortPickReason;
import com.stockflow.inventory.consumption.StockDamageReason;

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
     * A task's lines in walking order. The assignee, and managers and coordinators of the task's
     * warehouse, may read them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}, {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PickLineView> lines(UUID taskId, int page, int size, CurrentUser actor);

    /**
     * The assignee scanned a line: its location, then its carton's LPN - nothing else is accepted, and no quantity is
     * entered (07 BR-04, BR-10). Deducts the line's hold, the whole carton, in the same transaction (in-process,
     * {@code inventory :: consumption}). The same scan again is answered with the line as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (task, line, or not the
     *         assignee); {@code OUT_OF_DATA_SCOPE}; {@code PICK_TASK_STATE_CONFLICT};
     *         {@code PICK_LINE_NOT_OPEN}; {@code PICK_LOCATION_MISMATCH}; {@code PICK_ITEM_MISMATCH} for a SKU, a
     *         barcode or another carton's LPN; {@code RESERVATION_NOT_ACTIVE}; {@code INVENTORY_POLICY_STOCK_CONFLICT}
     */
    PickLineView confirm(UUID taskId, UUID lineId, String locationCode, String lpn, CurrentUser actor);

    /**
     * Picked lines of cancelled tasks still waiting to go back onto a bin, oldest first; limited to the
     * caller's warehouses and, when given, to one of them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<PutBackRow> putBacks(UUID warehouseId, int page, int size, CurrentUser actor);

    /**
     * Puts the carton a line picked back onto a bin of the task's warehouse, whole, confirmed by scanning its LPN (07
     * §4.5 step 14): it is IN_STOCK there again, sealed, AVAILABLE, with one {@code PUT_BACK} ledger line. Again for the
     * same line and LPN, it answers with the line as it is.
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
     * more the order resumes. A coordinator or the warehouse's manager.
     *
     * @return the new lines
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code PICK_LINE_NOT_OPEN} unless SHORT; {@code INSUFFICIENT_STOCK} when whole cartons cannot cover it
     *         - also for a damage report's share restored as loose units, which no pick line can take - and then
     *         nothing changes and the order stays ON_HOLD; {@code PICK_HOLD_NOT_IN_CARTON} should an allocation still
     *         land on no carton (a guard), likewise changing nothing
     */
    List<PickLineView> reallocateShort(UUID taskId, UUID lineId, CurrentUser actor);

    /**
     * SCRUM-277: the assignee is at the line's bin and finds its carton damaged, and reports it with photos
     * (BR-STK-003) instead of confirming the line - always the whole carton ({@code damagedQuantity} = the line's). In
     * one transaction, pick first, then order, then stock rows: inventory moves the units to quarantine as DAMAGED; the
     * line gives way to new lines where whole cartons of the warehouse replaced it - or, when there are too few, to a
     * SHORT line, the order ON_HOLD with reason {@code INVENTORY} until the coordinator reallocates it (the X-SHORT
     * path). The same report again answers with what it made. Kept until team plan 5-6 removes unit-level damage; a
     * short pick with reason {@code DAMAGED} is the docs' way (07 §4.4 step 9).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (task, line, or not the assignee);
     *         {@code OUT_OF_DATA_SCOPE}; {@code PICK_TASK_STATE_CONFLICT}; {@code PICK_LINE_NOT_OPEN};
     *         {@code PICK_LOCATION_MISMATCH}; {@code STOCK_PACKAGE_TAKEN_WHOLE}; {@code DAMAGE_PHOTO_REQUIRED},
     *         {@code UNSUPPORTED_MEDIA_TYPE}; {@code DAMAGE_AREA_MISSING} and the other refusals of inventory's report
     */
    PickDamageView reportDamage(UUID taskId, UUID lineId, PickDamage damage, IncomingFiles photos, CurrentUser actor);

    /**
     * A slot for one damage photo of the task, uploaded straight to the object store: for the task's assignee, in the
     * task's warehouse.
     */
    UploadSlot beginDamagePhotoUpload(UUID taskId, UploadSlotRequest request, CurrentUser actor);

    /**
     * What the picker reports.
     *
     * @param requestId          generated by the client once per report, sent again on a retry
     * @param locationCode       the bin, as scanned
     * @param damageLocationCode the quarantine area the picker takes the units to; null lets the system choose
     */
    record PickDamage(UUID requestId, String locationCode, int damagedQuantity, StockDamageReason reason, String note,
                      String damageLocationCode) { }

    /**
     * What a damage report at picking did.
     *
     * @param damageLocationCode where the picker takes the damaged units
     * @param orderOnHold        true when nothing replaced them: the new SHORT line waits for the coordinator
     * @param line               the line reported, now CANCELLED with its damaged units
     * @param lines              the lines that replaced it, in walking order: pick the OPEN ones
     */
    record PickDamageView(UUID damageReportId, String reportNumber, String damageLocationCode, int damagedQuantity,
                          boolean orderOnHold, PickLineView line, List<PickLineView> lines) { }

    /**
     * One line of a pick list.
     *
     * @param barcode the SKU's barcode, for the picker to read; a scan of it confirms nothing (07 BR-10)
     * @param shortQty the units of a SHORT line (or one reallocated since): the whole carton since 4C-4
     * @param shortReason why the carton could not be taken; null unless reported short since 4C-4
     * @param lpn the carton the line takes, scanned to confirm it and to put it back
     * @param damagedQty units found damaged at the bin; positive only on a line a damage report superseded
     * @param damageReportId the damage report that superseded or made the line; null otherwise
     * @param packingDamagedQty units of a picked line found damaged at the packing bench (R8): no longer packed or put
     *                          back; the lines a report made at packing pick what replaced them
     */
    record PickLineView(UUID lineId, UUID taskId, int sequenceNo, UUID orderLineId, String sku, String barcode,
                        String locationCode, String lotNumber, int allocatedQty, int pickedQty, String status,
                        Instant pickedAt, int putBackQty, String putBackLocationCode, Instant putBackAt,
                        int shortQty, ShortPickReason shortReason, String lpn, boolean wholeCarton, int damagedQty,
                        UUID damageReportId, int packingDamagedQty) { }

    /**
     * A picked line of a cancelled task, waiting to go back onto a bin.
     *
     * @param lpn       the carton that goes back, scanned to put it back
     * @param pickedQty what goes back: what the line picked, less what was found damaged at packing - the whole
     *                  carton, or the put-back is refused
     */
    record PutBackRow(UUID taskId, String orderNumber, UUID warehouseId, UUID lineId, String sku, String lotNumber,
                      String lpn, int pickedQty, String fromLocationCode, Instant pickedAt) { }
}
