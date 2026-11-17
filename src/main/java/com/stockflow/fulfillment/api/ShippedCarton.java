package com.stockflow.fulfillment.api;

/**
 * One carton that left on a shipment, as the pick that took it recorded it: its LPN, SKU, lot and the units picked in
 * it (a whole carton, team plan 4C-2).
 */
public record ShippedCarton(String lpn, String sku, String lotNumber, int quantity) {
}
