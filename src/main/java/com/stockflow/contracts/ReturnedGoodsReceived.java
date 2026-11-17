package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>procurement</b> module when a return receipt is confirmed (team plan 4D-2; kltn-docs 03 §4.2
 * step 12, §3 Output; 17 BR-18): goods that came back are in the warehouse, in quarantine at {@code locationCode}. The
 * receipt is CLOSED in the same transaction. Exactly one of {@code shipmentId} (a shipment the carrier brought back,
 * RTO) and {@code returnRequestId} (an approved return request, RMA) is set: what the receipt was against.
 *
 * <p>What it is the fact for: the <b>order</b> module takes it as the goods being back (the order {@code Returned},
 * the return request received), and <b>payment</b> as the ground for refunding them (kltn-docs 15) - those listeners
 * are team plan 4D-3 and 4B-9's. The cartons are the ones actually received, which may be fewer than the shipment or
 * return request carried (03 edge cases: the receipt is confirmed with the cartons that came); Sales follow up the
 * rest outside the system.</p>
 *
 * <p>Keyed by {@code receiptId}: a receipt is confirmed once, and a consumer that sees it twice must not count the
 * goods twice. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 */
public record ReturnedGoodsReceived(
        UUID receiptId,
        String receiptNumber,
        UUID warehouseId,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID shipmentId,
        UUID returnRequestId,
        String locationCode,
        List<Carton> cartons,
        UUID receivedBy,
        Instant receivedAt
) {

    public ReturnedGoodsReceived {
        cartons = List.copyOf(cartons);
    }

    /**
     * One carton received back, as it was shipped: its LPN, SKU, units and original lot (03 BR-03). {@code condition}
     * is what the warehouse noted of its outside (dented, wet, opened), null when nothing was.
     */
    public record Carton(String lpn, String sku, String lotNumber, int quantity, String condition) {
    }
}
