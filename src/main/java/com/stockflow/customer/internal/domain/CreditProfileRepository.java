package com.stockflow.customer.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CreditProfile}, one per customer. */
public interface CreditProfileRepository {

    Optional<CreditProfile> findByCustomerId(UUID customerId);

    CreditProfile save(CreditProfile profile);
}
