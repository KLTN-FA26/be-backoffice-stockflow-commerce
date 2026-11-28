package com.stockflow.customer.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for {@code customer.sales_owner_change_request}. */
public interface OwnerChangeRequestRepository {

    Optional<OwnerChangeRequest> findById(UUID id);

    /** The request under a row lock held to the end of the transaction. */
    Optional<OwnerChangeRequest> findForUpdate(UUID id);

    /** The customer's latest request (the newest asked), or empty. */
    Optional<OwnerChangeRequest> latestOf(UUID customerId);

    /** The customer's requests, newest first, at most {@code limit}. */
    List<OwnerChangeRequest> of(UUID customerId, int limit);

    /** One page of every customer's requests, newest first; a null status matches all. */
    List<OwnerChangeRequest> list(OwnerChangeRequest.Status status, int page, int size);

    long count(OwnerChangeRequest.Status status);

    void insert(OwnerChangeRequest request);

    void update(OwnerChangeRequest request);
}
