package com.stockflow.customer.api;

public record ListCustomersQuery(int page, int size, String search, String status, String sort,
                                 java.util.UUID segmentId) {

    /** No group filter. */
    public ListCustomersQuery(int page, int size, String search, String status, String sort) {
        this(page, size, search, status, sort, null);
    }
}
