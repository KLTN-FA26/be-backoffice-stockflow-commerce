package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>customer</b> once a salesperson's book has been released because their account stopped working as a
 * salesperson (sales-owner plan BR-S08, D44; {@link StaffAccountDeactivated}): the customers they owned now wait for a
 * Sales Manager to assign an owner (NEEDS_ASSIGNMENT, the former owner kept), and their open leads are back in the
 * managers' queue. One event per deactivation, after commit - the managers are told once, with the counts, rather than
 * once per customer (review finding C1). Not published when there was nothing to release, so a redelivered
 * deactivation, which finds nothing left, says nothing again.
 *
 * @param formerUserId the salesperson whose book it was
 * @param cause        the deactivation's {@link StaffAccountDeactivated#status()}: LOCKED, DISABLED or
 *                     {@link StaffAccountDeactivated#SALES_ROLE_REVOKED}
 * @param customerIds  the customers that now need an owner, in id order
 * @param openLeads    how many open leads went back to the queue
 * @param byUserId     who deactivated the account, null when no signed-in user did
 * @param at           when the account was deactivated (the deactivation's own time: one notice per deactivation)
 */
public record CustomersNeedSalesOwner(UUID formerUserId, String cause, List<UUID> customerIds, int openLeads,
                                      UUID byUserId, Instant at) {

    public CustomersNeedSalesOwner {
        Objects.requireNonNull(formerUserId, "formerUserId");
        Objects.requireNonNull(cause, "cause");
        customerIds = List.copyOf(Objects.requireNonNull(customerIds, "customerIds"));
        Objects.requireNonNull(at, "at");
    }
}
