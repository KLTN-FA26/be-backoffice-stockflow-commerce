package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * A carton as a return receipt sees it (team plan 4D-2): what it holds - its SKU, its standard count, its lot, which
 * it keeps from the receipt it first came in on (kltn-docs 03 BR-03) - and where and how it stands.
 *
 * @param quantity     the units it holds whole, its standard count ({@code nominal_qty})
 * @param lotNumber    its lot, the one it has kept since it was received; null for a carton of no lot
 * @param status       RECEIVING, IN_STOCK, CONSUMED, LOST or VOID ({@code inventory.stock_package.status}); a carton
 *                     that left the warehouse on an order is CONSUMED
 * @param locationCode where it stands, or stood when it left stock
 * @param returnOpenedAt when a return receipt found it opened or empty (WF-05 Q-18): it stayed CONSUMED and is never
 *                     taken back again; null for a carton that never came back so
 * @param returnOwnership whose goods it is after it last came back on a return request (WF-05 Q-4): PENDING while the
 *                     refund is undecided, COMPANY, CUSTOMER_HELD (the return refunded nothing: held for the customer,
 *                     out of the books), CUSTOMER_SENT_BACK or CUSTOMER_DESTROYED; null for one that never came back on
 *                     a return request
 * @param returnRequestId the return request it last came back on, with {@code returnOwnership}; null for one that
 *                     never came back on a return request, and wherever it is not read (only {@link CartonReturns#find}
 *                     reads it). A custody note ended as taken by a supplementary return asks it of the carton the
 *                     note names (confirmation audit CF-5): the carton must have come back on that return
 */
public record ReturnedCarton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                             String status, String locationCode, java.time.Instant returnOpenedAt,
                             String returnOwnership, UUID returnRequestId) {

    /** With its ownership, the return it came back on not read. */
    public ReturnedCarton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity, String status,
                          String locationCode, java.time.Instant returnOpenedAt, String returnOwnership) {
        this(stockPackageId, lpn, sku, lotNumber, quantity, status, locationCode, returnOpenedAt, returnOwnership,
                null);
    }

    /** As before ownership was kept (WF-05 Q-4). */
    public ReturnedCarton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity, String status,
                          String locationCode, java.time.Instant returnOpenedAt) {
        this(stockPackageId, lpn, sku, lotNumber, quantity, status, locationCode, returnOpenedAt, null);
    }

    /**
     * The customer's goods, held for them or destroyed on their behalf (WF-05 Q-4): never taken back on a receipt - the
     * carton never left the warehouse, or no longer exists.
     */
    public boolean customersGoods() {
        return "CUSTOMER_HELD".equals(returnOwnership) || "CUSTOMER_DESTROYED".equals(returnOwnership);
    }

    /** A carton that never came back opened. */
    public ReturnedCarton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity, String status,
                          String locationCode) {
        this(stockPackageId, lpn, sku, lotNumber, quantity, status, locationCode, null);
    }
}
