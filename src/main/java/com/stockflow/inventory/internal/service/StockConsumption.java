package com.stockflow.inventory.internal.service;

import java.util.UUID;

/**
 * Turns a hold into a real deduction, once the goods have physically been picked.
 *
 * <p><b>Why this is not on {@link com.stockflow.inventory.api.InventoryService}.</b> That interface is
 * the module's published API — every method on it is something any module may call, for ever.
 * Deducting stock is not that: exactly one caller, {@code fulfillment}, is entitled to say "these
 * units have left the shelf", and it does so over HTTP when a pick list is confirmed. Widening the
 * published port for one caller would hand every module in the system the ability to make stock
 * disappear.</p>
 *
 * <p>Public, and inside {@code internal}: visible to this module's own web layer, invisible to
 * every other module — {@code ModularityTest} rejects an import of {@code inventory.internal} from
 * anywhere else. That is exactly the reach this operation should have.</p>
 */
public interface StockConsumption {

    /**
     * Deduct the units a reservation was holding.
     *
     * @throws IllegalArgumentException if no reservation has that id
     * @throws IllegalStateException    if the hold was already released, expired or consumed —
     *                                  goods cannot be shipped against a promise that was given up
     */
    void consume(UUID reservationId);
}
