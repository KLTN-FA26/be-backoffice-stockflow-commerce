package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A SUBCONTRACT purchase order as production sees it.
 *
 * @param status   the purchase order's status on the new table (DRAFT, PENDING_APPROVAL, APPROVED,
 *                 CONFIRMED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED)
 * @param approved whether production may send the work out: APPROVED or anything after it
 *                 (BR-PRD-13), not CANCELLED
 */
public record SubcontractOrder(
        UUID purchaseOrderId,
        String poNumber,
        UUID productionOrderId,
        UUID supplierId,
        UUID warehouseId,
        String status,
        boolean approved,
        String finishedSku,
        int quantity,
        BigDecimal unitPrice,
        String currency,
        BigDecimal totalAmount,
        LocalDate expectedDate
) {
}
