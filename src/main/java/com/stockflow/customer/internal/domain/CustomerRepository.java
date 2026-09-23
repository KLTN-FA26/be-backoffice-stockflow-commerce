package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends AggregateRepository<Customer, UUID> {
    Optional<Customer> findForUpdate(UUID id);
    Optional<Customer> findByUserId(UUID userId);
    Optional<Customer> findByEmail(String email);
}
