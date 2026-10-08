package com.stockflow.inventory.internal.domain;

/**
 * "Does this location exist?" — asked before stock is moved to it, so a typo is a 404 with a real
 * error code rather than a foreign-key refusal from the ledger insert.
 *
 * <p>A port because the answer belongs to {@code warehouse}. Until {@code warehouse :: api}
 * publishes its location lookup (SCRUM-89, PR #48) the adapter reads the code off
 * {@code warehouse.storage_location} directly; switch it to the API call when that lands.</p>
 */
public interface LocationDirectory {

    boolean exists(LocationId location);
}
