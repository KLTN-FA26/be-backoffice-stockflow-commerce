package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;

import java.util.List;
import java.util.UUID;

/** Filter for {@link PurchaseOrderSearchRepository#search}. Public: {@code internal.service}
 *  builds it. Reused by both the SCRUM-113 list endpoint and SCRUM-119's reporting queries. */
public record PurchaseOrderSearchCriteria(UUID supplierId, List<PurchaseOrderStatus> statuses) {
}
