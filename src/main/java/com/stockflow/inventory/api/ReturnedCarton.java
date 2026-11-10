package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * A carton as a return receipt sees it (team plan 4D-2): what it holds - its SKU, its standard count, its lot, which
 * it keeps from the receipt it first came in on (kltn-docs 03 BR-03) - and where and how it stands.
 *
 * @param quantity     the units it holds whole, the count it was received with ({@code received_qty})
 * @param lotNumber    its lot, the one its stock row has kept since it was received; null for a carton of no lot
 * @param status       RECEIVING, IN_STOCK, CONSUMED, LOST or VOID ({@code inventory.stock_package.status}); a carton
 *                     that left the warehouse on an order is CONSUMED
 * @param locationCode where it stands, or stood when it left stock
 */
public record ReturnedCarton(UUID stockPackageId, String lpn, String sku, String lotNumber, int quantity,
                             String status, String locationCode) {
}
