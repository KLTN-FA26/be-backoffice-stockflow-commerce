package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;

import java.util.List;
import java.util.UUID;

public record PurchaseOrderSearchCriteria(UUID supplierId, UUID warehouseId, List<PurchaseOrderStatus> statuses,
                                          String search) {
}
