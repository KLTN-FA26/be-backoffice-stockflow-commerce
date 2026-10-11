package com.stockflow.procurement.api;

import com.stockflow.common.domain.Sku;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The SUBCONTRACT PO a production split raises (SCRUM-434).
 *
 * @param productionOrderId the SUBCONTRACTED child production order this PO pays for
 * @param warehouseId       the warehouse the finished goods come back to — the production order's
 * @param finishedSku       the printed item the subcontractor delivers
 * @param unitPrice         price of the print work per good unit, in {@code currency}
 * @param createdBy         who split the production order, for the PO's timeline
 */
public record CreateSubcontractOrderCommand(
        UUID productionOrderId,
        UUID supplierId,
        UUID warehouseId,
        Sku finishedSku,
        int quantity,
        BigDecimal unitPrice,
        String currency,
        LocalDate expectedDate,
        UUID createdBy
) {

    public CreateSubcontractOrderCommand {
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        Objects.requireNonNull(finishedSku, "finishedSku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(createdBy, "createdBy");
        currency = currency == null ? "VND" : currency;
    }
}
