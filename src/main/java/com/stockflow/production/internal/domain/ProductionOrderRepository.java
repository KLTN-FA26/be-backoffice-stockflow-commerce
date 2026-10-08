package com.stockflow.production.internal.domain;

import java.util.Optional;

/**
 * SCRUM-422 persistence port. insertOrGet must atomically claim a UNIQUE source key, wait for a
 * concurrent creator, and return the persisted winner. A find-then-insert implementation alone
 * is invalid. All calls in a release join one transaction; a conflict rolls back the whole batch.
 */
public interface ProductionOrderRepository {
    Optional<ProductionOrder> findBySource(ProductionOrder.Source source);
    ProductionOrder insertOrGet(ProductionOrder candidate);
}
