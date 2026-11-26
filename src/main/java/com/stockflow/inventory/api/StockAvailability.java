package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * A read model other modules may see: how much sellable stock sits at one location.
 *
 * <p>One row is one carton (ADR-0011): {@code stockItemId} carries the carton's id - the record kept its shape - and
 * the unit figures are the carton's count while it is in stock, held, and free.</p>
 *
 * <p>Note what is absent — no {@code StockPackage}, no JPA entity, no way to mutate anything.
 * A module that receives this can display it and reason about it, and that is all.</p>
 */
public record StockAvailability(
        UUID stockItemId,
        String sku,
        String locationCode,
        String lotNumber,
        int onHand,
        int reserved,
        int allocated,
        int available
) {
}
