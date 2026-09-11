package com.stockflow.inventory.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A read model other modules may see: how much sellable stock sits at one location.
 *
 * <p>Note what is absent — no {@code StockItem}, no JPA entity, no way to mutate anything.
 * A module that receives this can display it and reason about it, and that is all.</p>
 */
public record StockAvailability(
        UUID stockItemId,
        String sku,
        String locationCode,
        String lotNumber,
        LocalDate expiryDate,
        int onHand,
        int reserved,
        int allocated,
        int available
) {
}
