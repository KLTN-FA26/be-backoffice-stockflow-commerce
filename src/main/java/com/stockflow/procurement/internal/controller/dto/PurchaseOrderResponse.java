package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(name = "PurchaseOrder", description = "A purchase order to a supplier")
public record PurchaseOrderResponse(

        UUID purchaseOrderId,

        @Schema(example = "PO-20260913-000001")
        String poNumber,

        UUID supplierId,

        @Schema(example = "DRAFT")
        String status,

        String currency,
        BigDecimal totalAmount,
        LocalDate expectedAt,

        @Schema(description = "Empty on a list row - see the docs on PurchaseOrderSearchRepository for why.")
        List<POLineResponse> lines,

        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,

        @Schema(description = "BR-PO-003: true when another open PO for this supplier, delivery "
                + "date and at least one overlapping SKU already exists. A warning, not a rejection.")
        boolean possibleDuplicate,

        @Schema(description = "Set only once the order is CANCELLED.")
        String cancellationReason,

        @Schema(description = "Set only once the order is CLOSED_SHORT.")
        String closeShortReason,
        int paymentTermDays,
        int leadTimeDays,
        Instant sentAt,
        String supplierConfirmationStatus,
        Instant supplierRespondedAt,
        String supplierReference,
        String supplierResponseNote,
        @Schema(description = "NOT_SENT, QUEUED, UNKNOWN (legacy), RETRYING, FAILED (terminal), or DELIVERED (transport accepted, not supplier confirmation)")
        String deliveryStatus
) {
}
