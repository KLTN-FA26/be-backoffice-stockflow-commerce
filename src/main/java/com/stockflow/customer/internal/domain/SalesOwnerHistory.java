package com.stockflow.customer.internal.domain;

import java.util.List;
import java.util.UUID;

/** Port for the append-only history of a customer's Sales owner ({@code customer.customer_sales_owner_history}). */
public interface SalesOwnerHistory {

    /** Appends one change; it joins the transaction that changed the customer. */
    void record(SalesOwnerChange change);

    /** One page of a customer's changes, newest first. */
    List<SalesOwnerChange> of(UUID customerId, int page, int size);

    /** How many changes a customer has, for the page's total. */
    long countOf(UUID customerId);
}
