package com.stockflow.customer.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only figures of who owns which customers, for the Sales Managers' workload board (sales-owner plan §10.4,
 * {@code GET /api/v1/sales/workload}, which the order module serves because the documents counted on it are its own).
 * Each method is one statement whatever the number of people asked about.
 */
public interface SalesOwnerBook {

    /** The customers ASSIGNED to each of {@code userIds} (a former owner's NEEDS_ASSIGNMENT customers are not theirs). */
    List<OwnedCustomer> ownedBy(Collection<UUID> userIds);

    /**
     * How many customers each of {@code userIds} was given since {@code since} - assigned, transferred, claimed or
     * created by them (the history's rows naming them as the new owner): what a monthly capacity is measured against
     * (D13). Not counted (review finding P3): the backfill, and the customers a salesperson only holds for an absent
     * colleague (TEMPORARY_LEAVE) or gets back from one (RETURN_FROM_LEAVE) - neither is a new customer to them, and
     * counting them made a stand-in look full and a returning salesperson look as if they had been given their own book
     * again. People given none are absent.
     */
    Map<UUID, Integer> assignedSince(Collection<UUID> userIds, Instant since);

    /** The assignment settings of those of {@code userIds} who have any; the others are not paused and unlimited. */
    Map<UUID, AssignmentSetting> assignmentSettings(Collection<UUID> userIds);

    /** A customer and its owner, owned since {@code since}. */
    record OwnedCustomer(UUID customerId, UUID ownerId, Instant since) {
    }

    /** D13, Odoo's "pause" and "capacity": suggested for no new customer while paused; a month's intake, or none. */
    record AssignmentSetting(UUID userId, boolean paused, Integer capacityPerMonth) {
    }
}
