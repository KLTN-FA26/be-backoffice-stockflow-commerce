package com.stockflow.customer.api;

import java.util.UUID;

/**
 * A page of the customer list.
 *
 * @param salesOwnerId    customers whose stored owner (or the one who left them) is this account (sales-owner plan
 *                        BR-S11 "my customers", and "by salesperson"); null for all
 * @param salesOwnerState only customers in this state ({@code ASSIGNED}, {@code NEEDS_ASSIGNMENT}, {@code UNASSIGNED});
 *                        null for all
 * @param taxCodeMissing  true: only customers without a tax code (owner decision D41, the "tax code missing"
 *                        flag); null or false: all
 */
public record ListCustomersQuery(int page, int size, String search, String status, String sort, UUID segmentId,
                                 UUID salesOwnerId, SalesOwnerState salesOwnerState, Boolean taxCodeMissing) {

    /** No owner or tax code filter. */
    public ListCustomersQuery(int page, int size, String search, String status, String sort, UUID segmentId) {
        this(page, size, search, status, sort, segmentId, null, null, null);
    }

    /** No group filter. */
    public ListCustomersQuery(int page, int size, String search, String status, String sort) {
        this(page, size, search, status, sort, null);
    }
}
