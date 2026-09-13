package com.stockflow.procurement.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Input to {@link ProcurementService#createPurchaseOrder}. */
public record CreatePurchaseOrderCommand(
        UUID supplierId,
        String currency,
        LocalDate expectedAt,
        List<CreatePOLineCommand> lines
) {
}
