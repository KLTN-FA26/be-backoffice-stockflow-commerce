package com.stockflow.customer.internal.domain;

import java.util.List;
import java.util.UUID;

/** Persistence port for the append-only status history of customers. Joins the caller's transaction. */
public interface CustomerStatusHistory {

    void record(CustomerStatusChange change);

    /** Oldest first. */
    List<CustomerStatusChange> of(UUID customerId);
}
