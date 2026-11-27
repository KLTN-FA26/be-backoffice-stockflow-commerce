package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for leads ({@code customer.lead}) and their history ({@code customer.lead_history}). */
public interface LeadRepository {

    Optional<Lead> findById(UUID id);

    /** The lead under a row lock held to the end of the transaction. */
    Optional<Lead> findForUpdate(UUID id);

    /** Writes a new lead and its first history row. */
    void insert(Lead lead, LeadChange change);

    /** Writes the lead's new state and the history row of the change, in the caller's transaction. */
    void update(Lead lead, LeadChange change);

    /**
     * Whether a lead with this tax code or this e-mail (case-insensitive) was submitted at or after {@code since}: the
     * public form's 24-hour duplicate check (plan §10.7). Null values match nothing.
     */
    boolean submittedSince(String taxCode, String email, Instant since);

    /** One page of leads, newest first; null filters match everything. */
    List<Lead> list(LeadStatus status, UUID assignedTo, int page, int size);

    long count(LeadStatus status, UUID assignedTo);

    /** The lead's history, oldest first. */
    List<LeadChange> history(UUID leadId);

    /** How many leads {@code salesUserId} is still working on (ASSIGNED, CONTACTED). */
    long countOpenOf(UUID salesUserId);
}
