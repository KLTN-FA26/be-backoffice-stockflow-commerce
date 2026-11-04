package com.stockflow.order.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CancellationRequest}. The paged list is {@code CancellationRequestSearch}. */
public interface CancellationRequestRepository {

    Optional<CancellationRequest> findById(UUID id);

    boolean hasPending(UUID orderId);

    /** The order's request waiting for a decision, if any (there is at most one). */
    Optional<CancellationRequest> findPending(UUID orderId);

    /** The order's most recent request, if it has one (what the customer's portal shows, G3-2). */
    Optional<CancellationRequest> latestOf(UUID orderId);

    /** The status of each order's most recent request, for the orders that have one (a page of cards). */
    java.util.Map<UUID, CancellationRequestStatus> latestStatuses(java.util.Collection<UUID> orderIds);

    CancellationRequest save(CancellationRequest request);
}
