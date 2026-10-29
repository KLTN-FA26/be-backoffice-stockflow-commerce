package com.stockflow.inventory.consumption;

import com.stockflow.common.storage.FileUpload;

import java.util.List;

/**
 * Damage found while picking (SCRUM-277; docs 03 §5, 04c X-SHORT): the units an order's allocation holds at a bin are
 * reported damaged, with photos (BR-STK-003), in the pick line's own transaction.
 *
 * <p>On {@code inventory :: consumption}, not on {@code api}: it changes an order's allocation, the thing a pick line
 * consumes, and only fulfillment is entitled to say what a picker found at a bin. Inventory's own damage report
 * (a shelf check, free units only) is inventory's endpoint and needs no cross-module door.</p>
 *
 * <p>Two steps, because they want different locks. {@link #storePhotos} writes to object storage and takes no lock -
 * call it before locking the pick, so nobody waits on an upload. {@link #report} locks the stock rows; call it after
 * the pick (pick, then order, then stock rows - the order every caller keeps). Both join the caller's transaction:
 * if anything after them fails, the photos are deleted and the stock is untouched.</p>
 */
public interface PickDamageReporting {

    /**
     * Checks each photo (an image by its first bytes, at most {@code 5}, size limits of the evidence category),
     * scans it and stores it. Requires a transaction: a rollback deletes what was stored.
     *
     * @throws com.stockflow.common.error.BusinessException {@code DAMAGE_PHOTO_REQUIRED} for none,
     *         {@code VALIDATION_FAILED} for too many, {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE};
     *         {@code STORAGE_ERROR} when the store fails
     */
    List<DamagePhoto> storePhotos(List<FileUpload> photos);

    /**
     * The damaged units leave the allocation and the bin for a quarantine area, DAMAGED (one MOVE ledger line,
     * reference {@code DAMAGE_REPORT}); the allocation keeps the rest. The same quantity is allocated again for the
     * order in the warehouse, FEFO, on pickable stock - the bin itself included, its count is not in doubt - or, when
     * there is none, kept as an impaired allocation on the damaged units. Idempotent on the request id.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (unknown, or another order's);
     *         {@code RESERVATION_NOT_ACTIVE}; {@code VALIDATION_FAILED} for more units than the allocation keeps;
     *         {@code STOCK_STATUS_MISMATCH}, {@code STOCK_EXPIRED}; {@code DAMAGE_AREA_MISSING},
     *         {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} for the
     *         quarantine area; {@code DAMAGE_PHOTO_REQUIRED}; {@code OUT_OF_DATA_SCOPE}
     */
    ReportPickDamageResult report(ReportPickDamageCommand command);

    /**
     * Damage found at the packing bench among units a pick line already took - broken in the tote, or seen when they
     * were packed. Those units were deducted when picked, so they come back into stock as DAMAGED in a quarantine area
     * of the warehouse (one {@code PUT_BACK} ledger line from nowhere, reference {@code DAMAGE_REPORT}); the hold the
     * pick consumed stays consumed. The order is made whole as at picking: the same quantity allocated again, FEFO, on
     * pickable stock of the warehouse, or, when there is none, kept as an impaired allocation on the damaged units.
     * {@code command.reservationId} is the consumed hold; {@code retainedQuantity} is always 0. Idempotent on the
     * request id.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} (unknown, or another order's);
     *         {@code RESERVATION_NOT_ACTIVE} unless the hold was picked; {@code VALIDATION_FAILED} for more units than
     *         the pick took, less those reported damaged after it already; {@code DAMAGE_AREA_MISSING},
     *         {@code LOCATION_NOT_FOUND}, {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} for the
     *         quarantine area; {@code DAMAGE_PHOTO_REQUIRED}; {@code OUT_OF_DATA_SCOPE}
     */
    ReportPickDamageResult reportAfterPick(ReportPickDamageCommand command);

    /**
     * The report a request made at picking or at packing, as it was answered, with {@code alreadyReported} true; empty
     * when the request made none. Read-only: how a caller recognises a report sent again before it checks anything.
     */
    java.util.Optional<ReportPickDamageResult> reportedFor(java.util.UUID requestId);

    /**
     * A damage report made at picking, as {@link #report} answered it, now with {@code alreadyReported} true: how the
     * same report sent again is answered with where the damaged units went. Read-only. Empty for an unknown id or a
     * shelf report.
     */
    java.util.Optional<ReportPickDamageResult> reported(java.util.UUID damageReportId);
}
