package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the procurement module — the only package other modules may import.
 *
 * <p>Every parameter and return type is a record or enum declared in THIS package, never a domain
 * object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal} and {@code
 * theApiPublishesNoEntities} enforce it).
 */
public interface ProcurementService {

    /** A DRAFT order to an ACTIVE supplier, received into an ACTIVE warehouse (SCRUM-113/390). */
    PurchaseOrderSummary createPurchaseOrder(CreatePurchaseOrderCommand command);

    Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId);

    /** Paginated, filterable list; {@code lines} is empty on every row. */
    PageResponse<PurchaseOrderSummary> list(ListPurchaseOrdersQuery query);

    /** DRAFT → PENDING_APPROVAL, freezing the order as a revision (SCRUM-114). */
    PurchaseOrderSummary submit(UUID purchaseOrderId, UUID submittedBy);

    /** PENDING_APPROVAL → APPROVED. Four-eyes: {@code approverId} must not be who submitted it. */
    PurchaseOrderSummary approve(UUID purchaseOrderId, UUID approverId);

    /** PENDING_APPROVAL → DRAFT, with a reason. */
    PurchaseOrderSummary reject(UUID purchaseOrderId, UUID approverId, String reason);

    /** APPROVED → CONFIRMED: the order is locked and sent to the supplier (#36, plan Q1). */
    PurchaseOrderSummary confirm(UUID purchaseOrderId, UUID userId, SendPurchaseOrderCommand command);

    PurchaseOrderSummary recoverDelivery(UUID purchaseOrderId, RecoverPurchaseOrderDeliveryCommand command);

    PageResponse<PurchaseOrderDeliveryDecision> deliveryDecisions(UUID purchaseOrderId, int page, int size);

    PurchaseOrderSummary recordSupplierConfirmation(UUID purchaseOrderId, RecordSupplierConfirmationCommand command);

    /** → CANCELLED with a reason, while nothing has been received. A confirmed order's supplier is told. */
    PurchaseOrderSummary cancel(UUID purchaseOrderId, UUID userId, String reason);

    /** PARTIALLY_RECEIVED → CLOSED (SHORT_CLOSE): the rest is written off with a reason. */
    PurchaseOrderSummary closeShort(UUID purchaseOrderId, UUID userId, String reason);

    /** RECEIVED → CLOSED (NORMAL). */
    PurchaseOrderSummary close(UUID purchaseOrderId, UUID userId);

    List<PurchaseOrderStatusCount> statusDashboard();

    PageResponse<SupplierSpendSummary> supplierSpend(SupplierSpendReportQuery query);
}
