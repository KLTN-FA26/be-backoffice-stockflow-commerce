package com.stockflow.inventory.api;

import java.util.Collection;
import java.util.List;

/**
 * Stock the company owns that is not available-to-promise yet but will be, for a reorder suggestion (ADR-0010).
 *
 * <p>A receipt closes the purchase order line, so goods counted in drop out of "on order" the moment they arrive, yet
 * they are still INBOUND until put away and count towards no ATP. Units dispatched on a transfer are "counted in no
 * warehouse until receipt" (TransferOrderServiceImpl). A suggestion blind to either proposes buying what is already in
 * the yard or on the road. Company-wide, like {@link InventoryService#availableToPromise(com.stockflow.common.domain.Sku)}.</p>
 */
public interface IncomingStockQueries {

    /** The most SKUs one call answers for. */
    int MAX_SKUS = 100;

    /**
     * Every SKU of {@code skus} once, in the order given, with zeros when nothing is incoming.
     *
     * @param skus SKU codes as stored (upper case), at most {@value #MAX_SKUS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for more than
     *         {@value #MAX_SKUS} SKUs
     */
    List<IncomingStock> incoming(Collection<String> skus);

    /**
     * @param inbound    on hand in INBOUND status: received, waiting for putaway or for QC to pass it
     * @param quarantine on hand in QUARANTINE: held by QC for a second decision, so it may still be rejected. Shown,
     *                   and left to the planner rather than counted as coming
     * @param inTransit  shipped on a transfer between warehouses and not yet received or reported damaged
     */
    record IncomingStock(String sku, int inbound, int quarantine, int inTransit) {
    }
}
