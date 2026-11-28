package com.stockflow.customer.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's Sales owner: who it is, who may act for the customer, and changing it (sales-owner plan BR-S01..S12).
 *
 * <p>Owner decision D43: only four things make someone an owner - a manager's assignment ({@link #assign}), a claim
 * from the chat queue ({@link #claimIfUnowned}), the customer's creation and a lead's conversion. Creating a quote or
 * anything else for a customer with no owner never does: {@link #mayActFor} lets it happen, it does not claim.</p>
 *
 * <p>Every write locks the customer row, checks under the lock, writes the history row in the same transaction and
 * publishes {@code CustomerSalesOwnerChanged} (delivered after commit). Methods join the caller's transaction.</p>
 */
public interface SalesOwnership {

    /** The customer's stored owner; empty for an unknown customer. */
    Optional<CustomerSalesOwner> ownerOf(UUID customerId);

    /**
     * Whether {@code userId} may act for the customer in strict mode (BR-S12, D1): true for the owner, the owner's
     * cover in force on {@code day}, a holder of {@code customer-sales-owners:UPDATE} (a Sales Manager or an admin),
     * and anyone when the customer has no active owner (UNASSIGNED, NEEDS_ASSIGNMENT, or an owner whose account is not
     * ACTIVE). False for an unknown customer or a null user: callers check that the customer exists first.
     */
    boolean mayActFor(UUID userId, UUID customerId, LocalDate day);

    /**
     * {@link #mayActFor} as a refusal: does nothing when allowed.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_OWNED_BY_OTHER_SALES} (409), its message
     *         naming the owner ("Khách này do A phụ trách") and its details carrying {@code salesOwnerId} and
     *         {@code salesOwnerName}
     */
    void requireMayActFor(UUID userId, UUID customerId, LocalDate day);

    /**
     * Assigns or changes the owner (BR-S01, BR-S03, BR-S04). Assigning the owner a customer already has is a no-op
     * answering the owner as it is (no history row, no event).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND};
     *         {@code SALES_OWNER_VERSION_CONFLICT} when the customer changed since {@code expectedVersion};
     *         {@code SALES_OWNER_CHANGE_REASON_REQUIRED} without a reason (or OTHER without a note);
     *         {@code SALES_OWNER_NOT_ASSIGNABLE} when the new owner is not an active SALES_STAFF account (D42: a
     *         Sales Manager or an admin is never an owner)
     */
    CustomerSalesOwner assign(AssignSalesOwnerCommand command);

    /**
     * A salesperson takes a customer from the queue (BR-S05, D2): the claimer becomes the owner when the customer has no
     * active owner (reason CLAIMED_FROM_QUEUE; the managers learn it from {@code CustomerSalesOwnerChanged}).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND};
     *         {@code SALES_OWNER_NOT_ASSIGNABLE} when the claimer is not an active Sales agent;
     *         {@code CUSTOMER_UNASSIGNED_CLAIM_ONLY} when another active salesperson owns it and the claimer is not
     *         their cover (case 18)
     */
    ClaimOutcome claimIfUnowned(UUID customerId, UUID claimerId, LocalDate day);
}
