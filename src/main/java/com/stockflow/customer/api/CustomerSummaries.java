package com.stockflow.customer.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Many customers at once, for a report or an export that lists them (2026-11-14 plan, BE2): their code
 * ({@link CustomerSummary#code()}, the tax code a B2B customer is known by) and name on every row, read in one query
 * rather than one {@code findById} per row. Its own interface rather than more of {@link CustomerService}, which is the
 * customer's whole lifecycle: a report needs this and nothing else.
 */
public interface CustomerSummaries {

    /** Each of these customers that exists, whatever its status; unknown ids are absent, null or empty in, empty out. */
    Map<UUID, CustomerSummary> summariesOf(Collection<UUID> customerIds);
}
