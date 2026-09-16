package com.stockflow.procurement.internal.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Raw aggregate row from {@link PurchaseOrderJpaRepository#supplierSpend} — supplier code/name
 *  are not on this row since {@code purchase_order} has no JPA relation to {@code supplier}, only
 *  a same-schema id column; the adapter batch-fetches those separately. */
record SupplierSpendRow(UUID supplierId, BigDecimal totalSpend, Long orderCount) {
}
