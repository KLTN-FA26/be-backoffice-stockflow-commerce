package com.stockflow.inventory.internal.domain;

/** One live hold of an order, read without loading the stock item aggregate (SCRUM-466). */
public record HeldStock(String sku, String locationCode, int quantity) {
}
