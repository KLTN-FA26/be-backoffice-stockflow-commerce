package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * Held blanks a print subcontractor turned into goods that came back on a goods receipt leave stock (SCRUM-441,
 * decision Q9): counting both the blanks at the subcontractor and the goods received would double the stock. One
 * CONSUMPTION ledger line, reference {@code PRODUCTION_ORDER}. Not a loss: no adjustment, no approval — spoilt
 * blanks go through {@link WriteOffHeldStockCommand} with {@code SUBCONTRACT_LOSS} instead.
 */
public record ConsumeHeldStockCommand(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity,
                                      String note, UUID actorId) {

    public ConsumeHeldStockCommand {
        if (reservationId == null) throw new IllegalArgumentException("reservationId is required");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
    }
}
