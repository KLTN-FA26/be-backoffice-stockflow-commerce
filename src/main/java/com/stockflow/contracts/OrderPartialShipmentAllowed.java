package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when Sales allows an order to ship in parts (kltn-docs 17 §4.5; 08 §4.2 step 4):
 * the flag went from off to on. Only the change is published, not a repeated decision.
 *
 * <p>Fulfillment reads it: an order held {@code INVENTORY} only because a carton was removed at the check station
 * (08 §4.2 step 4) has nothing left to wait for once Sales lets the rest ship in a later part, so the hold is lifted
 * without a second request from Sales (WF4-F2, owner decision 2026-11-28).</p>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record OrderPartialShipmentAllowed(
        UUID orderId,
        String orderNumber,
        UUID decidedBy,
        Instant decidedAt
) {
}
