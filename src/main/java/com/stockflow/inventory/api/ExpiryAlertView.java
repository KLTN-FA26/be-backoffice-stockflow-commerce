package com.stockflow.inventory.api;

import java.time.LocalDate;

/**
 * One open expiry alert, as {@link StockAlertQueries} returns it.
 *
 * @param warehouseCode the warehouse prefix ({@code HCM})
 * @param daysToExpiry  {@code expiryDate - today} in business days (Asia/Ho_Chi_Minh); negative once expired
 * @param quantity      units of the lot the sweep last saw in that warehouse
 * @param expired       true for an EXPIRED alert, false for EXPIRING
 * @param acknowledged  someone has seen it; the condition is still open
 */
public record ExpiryAlertView(String sku, String warehouseCode, String lotNumber, LocalDate expiryDate,
                              Long daysToExpiry, int quantity, boolean expired, boolean acknowledged) {
}
