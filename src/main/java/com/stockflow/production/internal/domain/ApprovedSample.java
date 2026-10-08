package com.stockflow.production.internal.domain;

import com.stockflow.common.error.ConflictException;

import java.util.Objects;
import java.util.UUID;

/** Projection of a persisted APPROVED round, never of an editable draft. */
public record ApprovedSample(UUID sampleRequestId, UUID customerId, SampleSpecification specification) {
    public ApprovedSample {
        Objects.requireNonNull(sampleRequestId, "sampleRequestId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(specification, "specification");
    }

    public void requireMatches(UUID customer, SampleSpecification requested) {
        if (!customerId.equals(customer)
                || !specification.designSnapshotId().equals(requested.designSnapshotId())
                || !specification.designChecksum().equals(requested.designChecksum())
                || !specification.blankSku().equals(requested.blankSku())) {
            throw new ConflictException("Approved sample does not match the customer, design and blank");
        }
    }
}
