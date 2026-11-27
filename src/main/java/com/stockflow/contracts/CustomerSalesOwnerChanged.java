package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when a customer's Sales owner changed (sales-owner plan BR-S06): assigned at creation,
 * changed by a manager, claimed from the queue, moved by a bulk or temporary transfer, or returned. {@code chat} moves
 * the open conversations to the new owner (and tells the customer when {@code notifyCustomer}); notices sent later read
 * the new owner through {@code ResponsibleSales}.
 *
 * <p>Not published when a departed owner's customer is only marked as needing an owner: that is
 * {@link CustomersNeedSalesOwner}. Published inside the transaction that writes the change and its history row,
 * delivered after commit; keyed by {@code (customerId, at)}.</p>
 *
 * @param fromUserId     the previous owner (or former owner who left), null when the customer never had one
 * @param toUserId       the new owner
 * @param batchId        the transfer batch that moved it, null for a single change
 * @param notifyCustomer whether the customer should be told in the chat (D3; off for an internal change)
 * @param byUserId       who made the change, null for the system
 */
public record CustomerSalesOwnerChanged(UUID customerId, UUID fromUserId, UUID toUserId,
                                        SalesOwnerChangeReason reason, UUID batchId, boolean notifyCustomer,
                                        UUID byUserId, Instant at) {

    public CustomerSalesOwnerChanged {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(toUserId, "toUserId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
    }
}
