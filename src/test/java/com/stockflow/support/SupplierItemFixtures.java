package com.stockflow.support;

import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lists a SKU in a supplier's catalog, so a standard purchase order may take it (SCRUM-78, BR-PO-001).
 *
 * <p>Through the real {@link SupplierItemService} rather than an INSERT: a seeded row then obeys the
 * catalog's own rules (an ACTIVE supplier, a purchasable SKU, moq a whole number of packs) and opens
 * its price period the way a buyer's would.</p>
 */
public final class SupplierItemFixtures {

    private SupplierItemFixtures() {
    }

    /** Price 100000 in the supplier's currency, moq 1, pack 1, the supplier's lead time, not preferred. */
    public static SupplierItemSummary list(SupplierItemService catalog, UUID supplierId, String sku) {
        return list(catalog, supplierId, sku, new BigDecimal("100000"), 1, 1, null);
    }

    /** {@code leadTimeDays} null means the item uses the supplier's lead time. */
    public static SupplierItemSummary list(SupplierItemService catalog, UUID supplierId, String sku, BigDecimal price,
                                           int moq, int packSize, Integer leadTimeDays) {
        return catalog.create(supplierId, new SaveSupplierItemCommand(sku, null, price, moq, packSize, leadTimeDays,
                false, null));
    }
}
