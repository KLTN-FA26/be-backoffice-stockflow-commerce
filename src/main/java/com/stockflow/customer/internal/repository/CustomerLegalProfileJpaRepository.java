package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.entity.CustomerLegalProfileJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerLegalProfileJpaRepository extends JpaRepository<CustomerLegalProfileJpaEntity, UUID> {

    Optional<CustomerLegalProfileJpaEntity> findByCustomerId(UUID customerId);
}
