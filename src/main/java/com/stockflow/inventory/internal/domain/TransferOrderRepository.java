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
}
