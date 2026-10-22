package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link TransferOrder}. Listing lives on the search side. */
public interface TransferOrderRepository {

    Optional<TransferOrder> findById(UUID id);

    /** {@code TO-yyyyMMdd-0001}. */
    String nextNumber(LocalDate day);

    TransferOrder save(TransferOrder order);

    /** SCRUM-287 PR T: these transfers, read in one query (unknown ids left out). */
    java.util.List<TransferOrder> findAllById(java.util.Collection<UUID> ids);

    /** SCRUM-287 PR T: packed full truckloads still being picked at the warehouse - waiting for their load plan. */
    java.util.List<TransferOrder> findAwaitingDispatch(UUID warehouseId);
}
