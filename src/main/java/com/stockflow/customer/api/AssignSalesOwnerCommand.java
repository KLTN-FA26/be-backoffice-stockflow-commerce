package com.stockflow.customer.api;

import com.stockflow.contracts.SalesOwnerChangeReason;

import java.util.UUID;

/**
 * Give a customer a Sales owner, or change it (BR-S01, BR-S03). The permission ({@code customer-sales-owners:UPDATE})
 * is the caller's to check; the rules of the change are {@link SalesOwnership#assign}'s.
 *
 * @param reason          required; OTHER also needs a {@code note}
 * @param notifyCustomer  tell the customer in the chat (D3); off for an internal change
 * @param expectedVersion the customer's version as the caller read it ({@link CustomerSalesOwner#version})
 * @param actorUserId     who makes the change, recorded in the history; null for the system
 */
public record AssignSalesOwnerCommand(UUID customerId, UUID salesOwnerId, SalesOwnerChangeReason reason, String note,
                                      boolean notifyCustomer, long expectedVersion, UUID actorUserId) {
}
