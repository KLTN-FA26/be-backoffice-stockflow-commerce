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
    boolean isTaxCodeLive(String taxCode, UUID exceptCustomerId);
}
