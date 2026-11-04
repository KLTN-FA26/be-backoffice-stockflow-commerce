package com.stockflow.inventory.internal.service;

import java.util.UUID;

/**
 * Turns a hold into a real deduction by hold id alone: the operations entry point behind
 * {@code POST /api/v1/inventory/reservations/{id}/consumption}.
 *
 * <p><b>Not how picking deducts stock.</b> A confirmed pick line deducts in-process through
 * {@link com.stockflow.inventory.consumption.PickConsumption} (named interface
 * {@code inventory :: consumption}, SCRUM-275), inside the pick line's transaction and idempotent per
 * line. This interface stays for an operator who has to close a hold by hand; it runs the same
 * implementation, so the hold, the stock figure, the {@code PICK} ledger row (reference {@code ORDER})
 * and {@code StockDeducted} come out exactly as from a pick.</p>
 *
 * <p>Public, and inside {@code internal}: visible to this module's own web layer, invisible to every
 * other module — {@code ModularityTest} rejects an import of {@code inventory.internal} from anywhere
 * else.</p>
 */
public interface StockConsumption {

    /**
     * Deduct the units a reservation was holding.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} for an unknown
     *         id; {@code RESERVATION_NOT_ACTIVE} when the hold was already released, expired or consumed —
     *         goods cannot be shipped against a promise that was given up;
     *         {@code INVENTORY_POLICY_STOCK_CONFLICT} for blocked or expired stock
     */
    void consume(UUID reservationId, UUID actorId);
}
