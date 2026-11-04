package com.stockflow.procurement.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Input to {@link ProcurementService#reviseDraft}: the whole draft as the buyer now wants it
 * (SCRUM-462). {@code version} is the one the buyer read; a draft changed since is refused.
 * Supplier and currency are not here: they are the order's for good (BR-07).
 */
public record RevisePurchaseOrderCommand(
        long version,
        UUID warehouseId,
        LocalDate expectedAt,
        String note,
        List<RevisePOLineCommand> lines
) {
}
