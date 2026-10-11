package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a purchase order ({@code procurement.purchase_orders}). {@code lines} is empty on a
 * row of the paginated list; only a single-order read loads them.
 *
 * <p>{@code status} is one of DRAFT, PENDING_APPROVAL, APPROVED, CONFIRMED, PARTIALLY_RECEIVED,
 * RECEIVED, CLOSED, CANCELLED (decision D4). {@code sentAt} is {@code confirmedAt}: confirming an
 * order is sending it (plan Q1), and the field keeps its old name for the supplier-communication
 * screens. A short close is {@code CLOSED} with {@code closeKind = SHORT_CLOSE} and its reason in
 * {@code closeReason}.</p>
 */
public record PurchaseOrderSummary(
        UUID purchaseOrderId,
        String poNumber,
        String type,
        UUID productionOrderId,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        String status,
        String currency,
        LocalDate orderDate,
        LocalDate expectedAt,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal totalAmount,
        String note,
        List<POLineSummary> lines,
        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,
        boolean possibleDuplicate,
        long revisionNo,
        UUID submittedBy,
        Instant submittedAt,
        UUID approvedBy,
        Instant approvedAt,
        UUID confirmedBy,
        Instant confirmedAt,
        Instant closedAt,
        String closeKind,
        String closeReason,
        String cancellationReason,
        int paymentTermDays,
        int leadTimeDays,
        Instant sentAt,
        String supplierConfirmationStatus,
        Instant supplierRespondedAt,
        String supplierReference,
        String supplierResponseNote) {

    public PurchaseOrderSummary {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
