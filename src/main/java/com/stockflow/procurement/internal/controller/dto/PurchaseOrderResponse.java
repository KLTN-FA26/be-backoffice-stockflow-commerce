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
        @Schema(example = "PO-20260913-000001") String poNumber,
        @Schema(example = "STANDARD", description = "STANDARD, or SUBCONTRACT for print work") String type,
        UUID productionOrderId,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        UUID warehouseId,
        @Schema(description = "The warehouse prefix") String warehouseCode,
        String warehouseName,
        @Schema(example = "DRAFT", description = "DRAFT, PENDING_APPROVAL, APPROVED, CONFIRMED (= sent),"
                + " PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED") String status,
        String currency,
        LocalDate orderDate,
        LocalDate expectedAt,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal totalAmount,
        String note,
        @Schema(
                        description =
                                "Empty on a list row - see the docs on"
                                    + " PurchaseOrderSearchRepository for why.")
                List<POLineResponse> lines,
        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,
        @Schema(
                        description =
                                "BR-PO-003: true when another open PO for this supplier, delivery"
                                    + " date and at least one overlapping SKU already exists. A"
                                    + " warning, not a rejection.")
                boolean possibleDuplicate,
        @Schema(description = "The revision being approved or last approved") long revisionNo,
        UUID submittedBy,
        Instant submittedAt,
        UUID approvedBy,
        Instant approvedAt,
        UUID confirmedBy,
        Instant confirmedAt,
        Instant closedAt,
        @Schema(description = "NORMAL or SHORT_CLOSE once CLOSED") String closeKind,
        @Schema(description = "Why it was closed short") String closeReason,
        @Schema(description = "Set only once the order is CANCELLED.") String cancellationReason,
        int paymentTermDays,
        int leadTimeDays,
        @Schema(description = "Same as confirmedAt: confirming is sending") Instant sentAt,
        String supplierConfirmationStatus,
        Instant supplierRespondedAt,
        String supplierReference,
        String supplierResponseNote,
        @Schema(
                        description =
                                "NOT_SENT, QUEUED, UNKNOWN (legacy), RETRYING, FAILED (terminal),"
                                    + " or DELIVERED (transport accepted, not supplier"
                                    + " confirmation)")
                String deliveryStatus,
        @Schema(
                        description =
                                "Cancellation notice only: NOT_REQUIRED, UNKNOWN (legacy), QUEUED,"
                                    + " RETRYING, FAILED, DELIVERED")
                String cancellationDeliveryStatus,
        @Schema(description = "Non-blocking business warnings, e.g. DELIVERY_DATE_IN_PAST (BR-06)")
                List<String> warnings) {}
