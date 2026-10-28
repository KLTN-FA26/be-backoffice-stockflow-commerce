package com.stockflow.inventory.internal.service;

/**
 * What one carton label says (SCRUM-473). No item name: it lives in product's schema, which inventory may not
 * read, and the SKU identifies the item; the LPN scan gives the rest. No lot: a carton stores none, and the lot is on the receipt line, which
 * inventory cannot read; scanning the LPN is what leads to it.
 *
 *
 * @param receivedQty what was counted into the carton, null while it is still to be counted
 * @param unit        the packing unit's code, as the receipt shows it
 */
public record StockPackageLabel(String lpn, String sku, Integer receivedQty, int nominalQty,
                                String unit) {
}
