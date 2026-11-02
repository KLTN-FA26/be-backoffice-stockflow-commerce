package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a return request ends without being decided on its goods (03 §12):
 * {@code outcome = CANCELLED} - withdrawn before the goods came back, by the customer ({@code byCustomer}) or by Sales
 * for them - or {@code outcome = EXPIRED} - approved goods did not arrive by the deadline; {@code actorId} is null then,
 * the system expired it. Notification tells the customer; the order goes back to waiting for its return window and its
 * money.
 *
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record ReturnClosed(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String outcome,
        boolean byCustomer,
        UUID actorId,
        Instant closedAt
) {
}
