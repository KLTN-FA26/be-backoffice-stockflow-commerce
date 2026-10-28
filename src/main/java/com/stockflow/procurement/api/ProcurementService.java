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

    /**
     * A DRAFT order to an ACTIVE supplier, received into an ACTIVE warehouse (SCRUM-113/390), of SKUs the
     * supplier lists in quantities its catalog allows (SCRUM-78, BR-PO-001); otherwise SUPPLIER_TERMS_VIOLATED
     * with one field error per broken rule.
     */
    PurchaseOrderSummary createPurchaseOrder(CreatePurchaseOrderCommand command);

    Optional<PurchaseOrderSummary> findById(UUID purchaseOrderId);

    /** Paginated, filterable list; {@code lines} is empty on every row. */
    PageResponse<PurchaseOrderSummary> list(ListPurchaseOrdersQuery query);

    /**
     * Changes a DRAFT standard order — warehouse, delivery date, note, lines — in place (SCRUM-462,
     * kltn-docs 02 §5). Anything else is PURCHASE_ORDER_NOT_EDITABLE; a stale {@code version} is
     * OPTIMISTIC_LOCK. Every line is held to the supplier's catalog as it is now, as on create.
     */
    PurchaseOrderSummary reviseDraft(UUID purchaseOrderId, RevisePurchaseOrderCommand command);

    /**
     * DRAFT → PENDING_APPROVAL, freezing the order as a revision (SCRUM-114) - or straight to APPROVED, recorded as
     * {@code AUTO_APPROVED} with the limit used ({@code autoApproved}; kltn-docs 02 §4 step 7, SCRUM-124), when the
     * total after tax is within the approval limit of the submitter (for a subcontract order raised by splitting a
     * production order, of the person who split it), the order was never rejected, and with the same holder's
     * automatic approvals to the same supplier in the last 7 days it still is. No limit configured is no limit: the
     * order waits for an approver.
     */
    PurchaseOrderSummary submit(UUID purchaseOrderId, UUID submittedBy);

    /**
     * PENDING_APPROVAL → APPROVED. Four-eyes: {@code approverId} must not be who submitted it
     * (SELF_APPROVAL_NOT_ALLOWED), and the approver's own limit, when they have one, must cover the total (409 PO_APPROVAL_LIMIT_EXCEEDED).
     */
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

    /**
     * PARTIALLY_RECEIVED → CLOSED (SHORT_CLOSE): the rest is written off with a reason. CONFIRMED → CLOSED
     * (FORCE_CLOSE) when nothing was received and will not be (kltn-docs 02 §5); the reason is mandatory.
     */
    PurchaseOrderSummary closeShort(UUID purchaseOrderId, UUID userId, String reason);

    /** RECEIVED → CLOSED (NORMAL). */
    PurchaseOrderSummary close(UUID purchaseOrderId, UUID userId);

    List<PurchaseOrderStatusCount> statusDashboard();

    PageResponse<SupplierSpendSummary> supplierSpend(SupplierSpendReportQuery query);
}
