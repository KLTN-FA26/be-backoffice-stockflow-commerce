package com.stockflow.customer.api;

/**
 * Whether a customer has a Sales owner (sales-owner plan §2.1; {@code customer.customer.sales_owner_state}).
 */
public enum SalesOwnerState {
    /** An owner looks after the customer. */
    ASSIGNED,
    /** The owner left (account locked or disabled, BR-S08); the former owner is kept and a manager assigns one. */
    NEEDS_ASSIGNMENT,
    /** The customer never had an owner. */
    UNASSIGNED
}
