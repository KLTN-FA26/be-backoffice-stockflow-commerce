package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * The read side of purchase orders, kept off the aggregate's port because it needs
 * {@code Page}/{@code Pageable}, which {@code internal.domain} may not use. The summary carries the
 * supplier's and warehouse's names and each line's SKU and received quantity.
 */
public interface PurchaseOrderSearchRepository {

    Page<PurchaseOrderSummary> search(PurchaseOrderSearchCriteria criteria, Pageable pageable);

    Optional<PurchaseOrderSummary> summary(PurchaseOrderId id, boolean possibleDuplicate);
}
