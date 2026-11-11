package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** A proof of delivery kept with a shipment (kltn-docs 09 §4.3 step 2, BR-28); the bytes come from its download link. */
public record PodFileResponse(UUID fileId, UUID shipmentId, String fileName, String contentType, long sizeBytes,
                              UUID uploadedBy, Instant uploadedAt) {
}
