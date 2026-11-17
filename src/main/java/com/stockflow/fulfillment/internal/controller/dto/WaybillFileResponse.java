package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** A carrier's waybill document kept with a load (kltn-docs 09 §4.4, BR-28); the bytes come from its download link. */
public record WaybillFileResponse(UUID fileId, UUID loadId, String fileName, String contentType, long sizeBytes,
                                  UUID uploadedBy, Instant uploadedAt) {

    /** Step two of a file uploaded straight to storage: the upload id the slot gave. */
    public record Uploaded(@jakarta.validation.constraints.NotNull UUID uploadId) {
    }

    /** A short-lived link saving the file under its name. */
    public record Link(String url, Instant expiresAt) {
    }
}
