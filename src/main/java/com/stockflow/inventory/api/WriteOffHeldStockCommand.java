package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * Units of a hold in the print shop are to be written off: spoilt ({@code SCRAP}, SCRUM-398/399, decision Q4
 * 2026-10-12) or made into a sample that goes to the customer ({@code SAMPLE}, SCRUM-400). They leave the hold at
 * once and stay {@code IN_PRODUCTION} — usable by nobody, sellable to nobody — and a write-off of the layer they
 * are on is asked for. Stock falls only when a different person approves it (SCRUM-145); a
 * rejection leaves the units where they are, still unsellable.
 *
 * <p>The hold must be live and belong to {@code orderId} or to {@code productionOrderId}.</p>
 */
public record WriteOffHeldStockCommand(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity,
                                       StockAdjustmentReason reason, String note, UUID requestedBy) {

    public WriteOffHeldStockCommand {
        if (reservationId == null) throw new IllegalArgumentException("reservationId is required");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        if (reason != StockAdjustmentReason.SCRAP && reason != StockAdjustmentReason.SAMPLE
                && reason != StockAdjustmentReason.SUBCONTRACT_LOSS) {
            throw new IllegalArgumentException("Held goods are written off as SCRAP, SAMPLE or SUBCONTRACT_LOSS");
        }
        if (requestedBy == null) throw new IllegalArgumentException("requestedBy is required");
    }
}
