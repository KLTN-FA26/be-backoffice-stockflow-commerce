package com.stockflow.procurement.api;

import java.util.List;
import java.util.UUID;

/**
 * Input to {@link ProcurementService#list}.
 *
 * @param search matches the PO number
 * @param sort   {@code property,direction} pairs; see {@code common.persistence.SortWhitelist}
 */
public record ListPurchaseOrdersQuery(
        int page,
        int size,
        UUID supplierId,
        UUID warehouseId,
        List<String> statuses,
        String search,
        String sort
) {
}
