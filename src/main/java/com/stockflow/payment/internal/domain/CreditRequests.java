package com.stockflow.payment.internal.domain;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CreditRequest}. Joins the caller's transaction. */
public interface CreditRequests {

    Optional<CreditRequest> find(UUID id);

    /** Under a row lock held to the end of the transaction. */
    Optional<CreditRequest> findForUpdate(UUID id);

    /** The customer's open request ({@link CreditRequestStatus#open}), if any: there is at most one. */
    Optional<CreditRequest> findOpen(UUID customerId);

    /**
     * Writes the request as it is now.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CREDIT_REQUEST_ALREADY_OPEN} when another request of
     *         the customer is open ({@code uk_credit_request_open}); {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    CreditRequest save(CreditRequest request);

    /**
     * Newest first. Every filter is optional.
     *
     * @param customerId null for every customer's
     * @param status     null for every status
     */
    PageResponse<CreditRequest> page(UUID customerId, CreditRequestStatus status, int page, int size);
}
