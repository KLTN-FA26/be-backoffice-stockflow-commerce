package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link ReturnRequest}. The paged lists are {@code ReturnRequestSearch}. */
public interface ReturnRequestRepository {

    Optional<ReturnRequest> findById(UUID id);

    /**
     * The order a return is of, read without loading the return: a step locks the order first, and a return already
     * loaded (managed) before its lock would be upgraded with a version check and fail on a concurrent change instead
     * of being read as it now is.
     */
    Optional<UUID> orderOf(UUID returnId);

    /**
     * The request, its row locked until the caller's transaction ends, read as it is now. Callers lock the order
     * first, then the request - the order every return path takes them in.
     */
    Optional<ReturnRequest> findByIdForUpdate(UUID id);

    /** Idempotency: a request opened again with the same request id finds the first. */
    Optional<ReturnRequest> findByRequestId(UUID requestId);

    /** Every return of the order, oldest first: what the order's lines are still claimed for, and how they ended. */
    List<ReturnRequest> findByOrder(UUID orderId);

    /**
     * What the order's returns claim of its lines, line by line and delivery by delivery - all a new claim is bounded
     * by, in one statement, without loading each return whole (its evidence, parcels and history are not needed).
     */
    ReturnClaims claimsOf(UUID orderId);

    /**
     * Returns waiting for goods whose deadline passed before {@code now}, oldest deadline first, at most {@code limit} -
     * none of them with a parcel being counted on a draft receipt (WF-05 BE-23): such a return is not expired, and left
     * in the batch it would take a place from one that is, every hour.
     */
    List<UUID> awaitingReturnPast(Instant now, int limit);

    /** The next {@code RMA-yyyyMMdd-NNNN} of the day, from a counter that never hands a number out twice. */
    String nextRmaNumber(LocalDate day);

    ReturnRequest save(ReturnRequest request);

    /** The status history, oldest first. */
    List<ReturnTransition> history(UUID returnRequestId);
}
