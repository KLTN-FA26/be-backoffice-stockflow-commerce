package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the procurement module — the only package other modules may import.
 *
 * <p>Every parameter and return type is a record or enum declared in THIS package, never a
 * domain object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal} and
 * {@code theApiPublishesNoEntities} enforce it).</p>
 */
public interface ProcurementService {

    /**
     * SCRUM-113/WBS 3.2.1. Rejects a supplier that does not exist or is {@code INACTIVE}, a PO
     * with zero lines, and a line with {@code quantityOrdered <= 0} or a negative {@code unitPrice}
     * (the aggregate's own invariants — see {@code PurchaseOrder}/{@code PoLine}). Flags (does not
     * reject) a likely BR-PO-003 duplicate — see {@link PurchaseOrderSummary#possibleDuplicate}.
     */
    PurchaseOrderSummary createPurchaseOrder(CreatePurchaseOrderCommand command);

    Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId);

    /** Paginated, filterable list. {@code lines} is empty on every row — see
     *  {@link PurchaseOrderSummary}. */
    PageResponse<PurchaseOrderSummary> list(ListPurchaseOrdersQuery query);
}
