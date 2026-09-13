package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a purchase order, for the API. Flat and immutable — handing out the
 * {@code PurchaseOrder} aggregate would let a caller invoke its methods outside a transaction.
 *
 * <p>{@code lines} is empty on a row from the paginated list query, same reasoning and same
 * pagination trap as {@code product.api.ProductSummary}; only a single-PO read populates it.</p>
 *
 * <p>{@code possibleDuplicate} (BR-PO-003) is only ever {@code true} on the response to
 * {@link ProcurementService#createPurchaseOrder}, flagging that another open PO exists for the
 * same supplier, delivery date and at least one overlapping SKU — a warning, not a rejection, so
 * it is always {@code false} on every other read.</p>
 */
public record PurchaseOrderSummary(
        UUID purchaseOrderId,
        String poNumber,
        UUID supplierId,
        String status,
        String currency,
        BigDecimal totalAmount,
        LocalDate expectedAt,
        List<POLineSummary> lines,
        Instant createdAt,
        String createdBy,
        boolean possibleDuplicate
) {

    public PurchaseOrderSummary {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
