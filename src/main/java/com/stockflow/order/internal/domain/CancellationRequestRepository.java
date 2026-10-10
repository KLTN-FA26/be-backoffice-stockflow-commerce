package com.stockflow.order.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CancellationRequest}. The paged list is {@code CancellationRequestSearch}. */
public interface CancellationRequestRepository {

    Optional<CancellationRequest> findById(UUID id);

    boolean hasPending(UUID orderId);

    CancellationRequest save(CancellationRequest request);
}
