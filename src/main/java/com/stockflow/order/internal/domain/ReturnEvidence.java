package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A photo kept with a return ({@code ordering.return_request_evidence}, append-only): the customer's picture of the
 * problem, or the warehouse's and QC's of what arrived. The file lives in object storage under {@code fileKey}; it is
 * served only through a short presigned link, never from the application's origin.
 */
public record ReturnEvidence(UUID id, Stage stage, String fileKey, String originalName, String contentType,
                             long sizeBytes, UUID uploadedBy, Instant uploadedAt) {

    /** When it was taken: with the claim, at receipt, at inspection. */
    public enum Stage { CLAIM, RECEIPT, INSPECTION }

    public ReturnEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(fileKey, "fileKey");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("An evidence file is not empty");
        }
    }
}
