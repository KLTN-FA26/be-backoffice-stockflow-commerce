package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by <b>order</b>'s nightly run, once per contract revision, when a sent contract is still unsigned past the day
 * the customer was to sign by. Nothing is cancelled: the salesperson who looks after the customer extends the deadline,
 * chases the customer or cancels the contract.
 */
public record ContractSignDeadlinePassed(UUID orderId, UUID contractId, int revision, LocalDate signBy,
                                         String contractNumber, String orderNumber, UUID customerId,
                                         Instant occurredAt) {
}
