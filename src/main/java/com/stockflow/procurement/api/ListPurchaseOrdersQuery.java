package com.stockflow.procurement.api;

import java.util.List;
import java.util.UUID;

public record ListPurchaseOrdersQuery(
        int page,
        int size,
        UUID supplierId,
        List<String> statuses,
        String sort
) {
}
