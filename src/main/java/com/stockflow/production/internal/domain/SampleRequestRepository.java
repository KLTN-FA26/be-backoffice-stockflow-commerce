package com.stockflow.production.internal.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * SCRUM-422 adapter contract. Lock and refresh the aggregate plus rounds until the application
 * transaction ends. Save must compare the persisted version with expectedVersion (CAS), persist
 * immutable review history, and flush before event publication. No adapter exists yet.
 */
public interface SampleRequestRepository {
    Optional<SampleRequest> findByIdForUpdate(UUID id);
    void insert(SampleRequest sample);
    void save(SampleRequest sample, long expectedVersion);
}
