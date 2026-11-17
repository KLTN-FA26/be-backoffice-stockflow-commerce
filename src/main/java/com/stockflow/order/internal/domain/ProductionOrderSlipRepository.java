package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The slips production orders were sent with (4A-3). */
public interface ProductionOrderSlipRepository {

    /** Keeps the slip, in the caller's transaction; a production order has one, written once. */
    void record(ProductionOrderSlip slip);

    Optional<ProductionOrderSlip> find(UUID productionOrderId);

    /** Of {@code keys}, those a slip names: for the orphaned-file sweep. */
    Set<String> referencedKeys(Collection<String> keys);
}
