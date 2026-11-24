package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * WF-05 F-5 (Q-12): Sales close a shipment the carrier lost.
 *
 * @param evidenceUploadId the carrier's confirmation of the loss, uploaded through
 *                         {@code .../lost-closure/evidence/uploads}; required ({@code ORDER_SHIPMENT_LOSS_EVIDENCE_REQUIRED})
 * @param version          the order's version as read; a closure on a changed order is refused
 */
public record CloseLostShipmentRequest(
        @Schema(description = "Why the shipment is closed as lost: the carrier's case, what the customer chose")
        @NotBlank @Size(max = 1000) String note,
        @Schema(description = "The upload of the carrier's confirmation of the loss, from "
                + ".../lost-closure/evidence/uploads (required)")
        UUID evidenceUploadId,
        @Schema(description = "The order's version as read; optional", nullable = true)
        Long version) {
}
