package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a print sample round of a production order is opened for the customer to
 * answer, or answered (kltn-docs 17 §4.3 steps 9-10; 16 §4.5 steps 11-12, BR-10). The chat puts the round's approval
 * card into the customer's conversation about the production order and shows the answer on it, wherever the customer
 * gave it (on the card or under "my designs").
 *
 * <p>Facts only: the customer's note and the photos stay on the round. Consumers receive it in-process; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param status {@code WAITING} (opened, waiting for the customer), {@code APPROVED} or {@code REPRINT_REQUESTED}
 */
public record SampleRoundChanged(UUID roundId, UUID productionOrderId, String productionOrderCode, UUID customerId,
                                 int roundNo, String status, Instant occurredAt) {
}
