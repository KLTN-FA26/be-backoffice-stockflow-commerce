package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;

import java.util.UUID;

/**
 * <b>Aggregate root of the customer module.</b>
 *
 * <p>STARTER STUB. The aggregate owns its invariants and is the only thing loaded and saved as a
 * unit. Keep it {@code final} and keep every framework annotation off it — that is what lets its
 * unit test run with no Spring context, and {@code ArchitectureTest.domainDoesNotDependOnFrameworks}
 * enforces it.</p>
 *
 * <p>Fill in: the fields that must be consistent together, a factory for each way the aggregate is
 * born, and one method per state transition — each checking its precondition and calling
 * {@code registerEvent(...)}. See {@code inventory.internal.domain.StockItem} for the worked
 * example and {@code docs/adding-a-module.md} §4.2.</p>
 */
public final class Customer extends AggregateRoot {

    private final UUID id;

    private Customer(UUID id) {
        this.id = java.util.Objects.requireNonNull(id, "id");
    }

    /** TODO: replace with the real birth of the aggregate, taking the fields it needs. */
    public static Customer create(UUID id) {
        return new Customer(id);
    }

    public UUID id() {
        return id;
    }
}
