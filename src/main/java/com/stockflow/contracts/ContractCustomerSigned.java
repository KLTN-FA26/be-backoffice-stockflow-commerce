package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by <b>order</b> when the customer has signed a sent contract on the portal (with a one-time code, or by
 * sending a signed file): the contract is CUSTOMER_SIGNED and waits for the company's authorised signer. The salesperson
 * who looks after the customer is told, and so are the holders of the permission to sign for the company.
 *
 * <p>Facts only - who signed and how are on the contract's signature evidence, read through
 * {@code GET /orders/{orderId}/contract/signatures}. Published inside the transaction that records the signature,
 * delivered after commit.</p>
 */
public record ContractCustomerSigned(UUID orderId, UUID contractId, int revision, String contractNumber,
                                     String orderNumber, UUID customerId, Instant occurredAt) {
}
