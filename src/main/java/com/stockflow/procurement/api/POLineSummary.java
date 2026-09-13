package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a {@link PurchaseOrderSummary}. */
public record POLineSummary(
        UUID lineId,
        String sku,
        String description,
        int quantityOrdered,
        int quantityReceived,
        BigDecimal unitPrice
) {

    /** {@code quantityOrdered - quantityReceived} — SCRUM-116/WBS 3.2.4.1's open-quantity calc. */
    public int openQuantity() {
        return quantityOrdered - quantityReceived;
    }
}
