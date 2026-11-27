package com.stockflow.inventory.internal.service;

/**
 * What one carton label says (SCRUM-473). Everything but the LPN is for the eye: a picture of the carton when the label
 * was printed. The name and the lot are asked of the modules that own them (see {@link StockPackageLabels}).
 *
 * @param name        what the SKU is called; null when the catalogue does not answer
 * @param lot         the lot the carton is in; null when it is not known
 * @param counted     whether the carton has been counted in; false while it is still to be counted
 * @param unit        the packing unit's code, as the receipt shows it
 */
public record StockPackageLabel(String lpn, String sku, String name, String lot, boolean counted, int nominalQty,
                                String unit) {
}
