package com.stockflow.customer.internal.domain;

import java.util.List;
import java.util.UUID;

/** Persistence port for the append-only history of a customer's name and tax code. Joins the caller's transaction. */
public interface CustomerChangeLog {

    void record(CustomerChange change);

    /** Oldest first. */
    List<CustomerChange> of(UUID customerId);
}
