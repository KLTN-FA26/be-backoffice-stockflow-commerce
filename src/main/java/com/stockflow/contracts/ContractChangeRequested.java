package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by <b>order</b> when the customer, reading a sent contract on the portal, asks for changes: the contract goes
 * back to a draft as the next revision. The salesperson who looks after the customer is told (not every salesperson).
 *
 * <p>No reason here: events carry facts and codes, not what somebody typed (the build refuses free text in
 * {@code contracts}). The customer's reason is on the contract's revision ({@code sales_contract_revision}), read through
 * {@code GET /orders/{orderId}/contract/revisions}. Published inside the transaction that writes the change, delivered
 * after commit.</p>
 *
 * @param revision the revision that ended - the one the customer read; the draft that follows is {@code revision + 1}
 */
public record ContractChangeRequested(UUID orderId, UUID contractId, int revision, String contractNumber,
                                      String orderNumber, UUID customerId, Instant occurredAt) {
}
