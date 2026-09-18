package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;
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

    /** SCRUM-116/WBS 3.2.4. DRAFT -&gt; APPROVED. */
    PurchaseOrderSummary approve(UUID purchaseOrderId);

    /** SCRUM-116/WBS 3.2.4. APPROVED -&gt; SENT. */
    PurchaseOrderSummary send(UUID purchaseOrderId);

    PurchaseOrderSummary recordSupplierConfirmation(UUID purchaseOrderId,
                                                     RecordSupplierConfirmationCommand command);

    /** SCRUM-116/WBS 3.2.4. DRAFT/APPROVED/SENT -&gt; CANCELLED. Rejected once anything has been
     *  received against the order. */
    PurchaseOrderSummary cancel(UUID purchaseOrderId, String reason);

    /**
     * SCRUM-116/WBS 3.2.4.1. Advances the named lines' received quantities; the order rolls up to
     * {@code CLOSED} once every line is fully received, or {@code PARTIALLY_RECEIVED} otherwise.
     *
     * <p>Only the PO-line running totals are updated. A dedicated goods-receipt record (receipt
     * number, QC outcome, discrepancies) is not created here — {@code GoodsReceiptJpaEntity} is
     * still a starter stub with no line-item table, and inventing that shape without a groomed
     * story for it would be guessing rather than implementing. Flagged as a gap, same treatment
     * BR-PO-001/002 got above.</p>
     */
    PurchaseOrderSummary receiveGoods(UUID purchaseOrderId, ReceiveGoodsCommand command);

    /** SCRUM-116/WBS 3.2.4. PARTIALLY_RECEIVED -&gt; CLOSED_SHORT: the remaining open quantity is
     *  written off. */
    PurchaseOrderSummary closeShort(UUID purchaseOrderId, String reason);

    /**
     * SCRUM-119/WBS 3.2.7. One row per {@code PurchaseOrderStatus}, including a status with zero
     * purchase orders right now. Not paginated — the house rule (always paginate list endpoints)
     * targets growable collections, and this is a fixed seven-bucket summary, not a list.
     */
    List<PurchaseOrderStatusCount> statusDashboard();

    /**
     * SCRUM-119/WBS 3.2.7. Suppliers ranked by total spend, highest first, optionally filtered by
     * supplier and/or {@code expectedAt} date range.
     *
     * <p><b>What counts as "spend", a judgment call the ticket does not define:</b> the sum of
     * {@code totalAmount} over orders that are not {@code DRAFT} (not yet a real commitment) and
     * not {@code CANCELLED} (never fulfilled) — {@code APPROVED}, {@code SENT},
     * {@code PARTIALLY_RECEIVED}, {@code CLOSED}, {@code CLOSED_SHORT}. Flagged here rather than
     * guessed silently, same treatment BR-PO-001/002 got above.</p>
     *
     * <p>A {@code supplierId} that matches a real supplier but has no qualifying orders returns an
     * empty page. A {@code supplierId} that does not name a real supplier at all throws
     * {@code SUPPLIER_NOT_FOUND} (404) instead — the two are different answers and must not look
     * the same, per the ticket's own unhappy-case list.</p>
     */
    PageResponse<SupplierSpendSummary> supplierSpend(SupplierSpendReportQuery query);
}
