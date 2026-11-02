package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * A putaway task as another module sees it: a snapshot, not the aggregate.
 *
 * @param targetLocationId the bin of the latest putaway; {@code null} until something has been put away
 * @param status           {@code PENDING}, {@code IN_PROGRESS}, {@code DONE} or {@code CANCELLED}; a string so the
 *                         module's own enum stays internal
 */
public record PutawayTaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                              UUID stockPackageId, UUID targetLocationId, String status) {
}
