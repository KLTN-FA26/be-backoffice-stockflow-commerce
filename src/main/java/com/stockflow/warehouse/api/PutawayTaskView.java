package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * A putaway task as another module sees it: a snapshot, not the aggregate.
 *
 * @param goodsReceiptId   null for a {@link PutawaySource#QUARANTINE_RELEASE} task, as is {@code goodsReceiptLineId}
 * @param targetLocationId the bin of the latest putaway; {@code null} until something has been put away
 * @param status           {@code PENDING}, {@code IN_PROGRESS}, {@code DONE} or {@code CANCELLED}; a string so the
 *                         module's own enum stays internal
 * @param source           why the task exists (05 BR-01)
 */
public record PutawayTaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                              UUID stockPackageId, UUID targetLocationId, String status, PutawaySource source) {

    /** A task of a goods receipt line. */
    public PutawayTaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                           UUID stockPackageId, UUID targetLocationId, String status) {
        this(id, goodsReceiptId, goodsReceiptLineId, sku, quantity, stockPackageId, targetLocationId, status,
                PutawaySource.RECEIPT);
    }
}
