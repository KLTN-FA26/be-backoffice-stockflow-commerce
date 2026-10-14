package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * What one pick line took off the books.
 *
 * @param alreadyConsumed true when this pick line had been deducted before and nothing changed now
 */
public record ConsumedPick(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                           boolean alreadyConsumed) {
}
