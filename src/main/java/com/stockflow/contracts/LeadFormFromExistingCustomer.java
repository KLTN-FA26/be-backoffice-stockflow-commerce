package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> when the public "open a business account" form named the tax code of a business that is
 * already a customer (sales-owner plan §10.7). No lead is made - an existing customer posing as a new one must not reach
 * another salesperson that way - and the sender gets the same neutral answer as anyone (no hint that it is a customer).
 * The customer's responsible salesperson is told instead: {@code ResponsibleSales} names them (owner, cover, or the
 * managers). After commit.
 *
 * <p>Facts only: what the form said stays out of the event, as it stays out of the database.</p>
 */
public record LeadFormFromExistingCustomer(UUID customerId, Instant at) {

    public LeadFormFromExistingCustomer {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(at, "at");
    }
}
