package com.stockflow.inventory.api;

/**
 * What one live hold of an order keeps back and where it sits (SCRUM-466): fulfillment reads it to
 * learn which warehouse an order not yet released to one will be picked from. Not the hold itself —
 * no reservation id, expiry or lot, which nobody outside inventory needs to decide that.
 */
public record OrderHold(String sku, String locationCode, int quantity) {
}
