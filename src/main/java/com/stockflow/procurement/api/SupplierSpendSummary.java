package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SCRUM-119/WBS 3.2.7. One supplier's row in the spend report, highest spend first.
 *
 * <p>{@code totalSpend} and {@code purchaseOrderCount} only count orders past {@code DRAFT} that
 * were not {@code CANCELLED} — see {@link ProcurementService#supplierSpend} for why. A supplier
 * with no such orders in the requested filter does not appear as a zero row; it is simply absent
 * from the page, same reasoning as {@code PurchaseOrderSummary.possibleDuplicate}'s "absence means
 * no" convention elsewhere in this module.</p>
 */
public record SupplierSpendSummary(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        BigDecimal totalSpend,
        long purchaseOrderCount
) {
}
