package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when an order's sales contract is sent to the customer, recorded signed or
 * cancelled (kltn-docs 14 §4.3 steps 13-16). The chat puts the contract's card into the customer's conversation when it
 * is sent (16 §4.3 step 8) and follows it afterwards.
 *
 * <p>Facts only: the cancellation reason and the files stay on the contract. Consumers receive it in-process; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param status    {@code SENT}, {@code SIGNED} or {@code CANCELLED}, the order module's names
 * @param changedBy the Sales account that took the step
 */
public record SalesContractStatusChanged(UUID contractId, String contractNumber, UUID orderId, String orderNumber,
                                         UUID customerId, String status, UUID changedBy, Instant occurredAt) {
}
