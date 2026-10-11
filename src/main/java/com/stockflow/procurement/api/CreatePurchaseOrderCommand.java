package com.stockflow.procurement.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Input to {@link ProcurementService#createPurchaseOrder}. {@code warehouseId} is where the goods are received (SCRUM-390). */
public record CreatePurchaseOrderCommand(
        UUID supplierId,
        UUID warehouseId,
        String currency,
        LocalDate expectedAt,
        String note,
        List<CreatePOLineCommand> lines
) {
}
