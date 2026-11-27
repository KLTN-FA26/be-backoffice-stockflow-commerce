package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends AggregateRepository<Customer, UUID> {
    Optional<Customer> findForUpdate(UUID id);
    Optional<Customer> findByUserId(UUID userId);
    /** The customer linked to an account, under a row lock held to the end of the transaction. */
    Optional<Customer> findByUserIdForUpdate(UUID userId);
    Optional<Customer> findByEmail(String email);
    /** Whether a PENDING_ACTIVATION or ACTIVE customer other than {@code exceptCustomerId} has this code. */
    /** BR-01 (decided 16/10/2026): another customer holds this normalised tax code, in any status. */
    boolean isTaxCodeTaken(String taxCode, UUID exceptCustomerId);

    /** The customer holding this normalised tax code, in any status (the code is unique). */
    Optional<UUID> idByTaxCode(String taxCode);

    /**
     * The stored Sales owner of each of these customers that exists, in one query without loading the customers
     * (ResponsibleSales asks for a page of them at once). Unknown ids are left out.
     */
    java.util.Map<UUID, SalesOwner> salesOwners(java.util.Collection<UUID> customerIds);

    /** The ids of the customers whose stored owner is {@code userId} in {@code state}, in id order. */
    java.util.List<UUID> idsOwnedBy(UUID userId, com.stockflow.customer.api.SalesOwnerState state);
}
