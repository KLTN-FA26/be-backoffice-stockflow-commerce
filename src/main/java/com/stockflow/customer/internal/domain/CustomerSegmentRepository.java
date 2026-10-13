package com.stockflow.customer.internal.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of {@link CustomerSegment} ({@code customer.segment}). Joins the caller's transaction. */
public interface CustomerSegmentRepository {

    Optional<CustomerSegment> find(UUID id);

    /** Under a row lock held to the end of the transaction. */
    Optional<CustomerSegment> findForUpdate(UUID id);

    boolean codeTaken(String code);

    /** Every group, by code. Groups are few: a handful per business, not a paged table. */
    List<CustomerSegment> all();

    /** How many customers each group holds; a group with none is absent. */
    Map<UUID, Long> customerCounts();

    /**
     * Inserts or writes the group, flushed, so a duplicate code fails here as
     * {@link org.springframework.dao.DataIntegrityViolationException}; returns it as stored (new version).
     */
    CustomerSegment save(CustomerSegment segment);
}
