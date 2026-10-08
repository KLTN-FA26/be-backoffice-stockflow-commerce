package com.stockflow.production.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Optional;
import java.util.UUID;

/** Must query only APPROVED, immutable rounds and retain customer ownership in both lookups. */
public interface ApprovedSampleRepository {
    Optional<ApprovedSample> findById(UUID sampleRequestId);
    Optional<ApprovedSample> findForDesign(UUID customerId, UUID designSnapshotId, Sku blankSku);
}
