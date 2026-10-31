package com.stockflow.procurement.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.PendingAmendment;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Amending an APPROVED or CONFIRMED standard purchase order (SCRUM-117, kltn-docs 02 BR-02): the change is frozen as
 * a revision of its own, decided like the original order - automatically within the requester's limit, otherwise by
 * someone else whose limit covers the new total - and only an approval writes it into the order.
 *
 * <p>Its own class rather than more of {@code ProcurementServiceImpl}, which is already the largest service of the
 * module. The rules are the aggregate's ({@link PurchaseOrder#proposeAmendment} and the methods after it), the
 * catalog checks {@link AmendmentLineChecks}', the limits {@link ApprovalLimits}'; this orders them under one lock.
 * Every method takes the purchase order row first ({@code SELECT ... FOR UPDATE}), the lock receiving takes too, so a
 * decision and the first goods receipt of the order never interleave.</p>
 *
 * <p>Public, unlike the module's other services, because its one caller - {@code PurchaseOrderController} - is in
 * another package and there is one implementation, with no port for another module to reach it through (as
 * {@code SkuUnitService} in product). Its methods are public so the transaction and audit proxies apply.</p>
 */
@Service
@Transactional
public class PurchaseOrderAmendments {

    private final PurchaseOrderRepository purchaseOrders;
    private final PurchaseOrderSearchRepository search;
    private final AmendmentLineChecks lineChecks;
    private final ApprovalLimits approvalLimits;
    private final SupplierAmendmentNotice supplierNotice;
    private final Clock clock;

    PurchaseOrderAmendments(PurchaseOrderRepository purchaseOrders, PurchaseOrderSearchRepository search,
                            AmendmentLineChecks lineChecks, ApprovalLimits approvalLimits,
                            SupplierAmendmentNotice supplierNotice, Clock clock) {
        this.purchaseOrders = purchaseOrders;
        this.search = search;
        this.lineChecks = lineChecks;
        this.approvalLimits = approvalLimits;
        this.supplierNotice = supplierNotice;
        this.clock = clock;
    }

    /**
     * Asks for an amendment: checked, frozen as revision n+1 and left waiting, then approved at once when the new total
     * is within the requester's own limit (SCRUM-124 rules 1, 3 and 4, as a submission). Everything that can refuse it
     * runs before the revision is written, since a revision is append-only and must not outlive a refusal.
     *
     * @param version the order's version as the requester read it
     * @throws BusinessException {@code OPTIMISTIC_LOCK}; {@code PO_AMENDMENT_NOT_ALLOWED} (not amendable, or a goods
     *                           receipt is being counted or waits); {@code PO_AMENDMENT_ALREADY_PENDING};
     *                           {@code VALIDATION_FAILED}; {@code PO_AMENDMENT_NO_CHANGE}; the catalog refusals of
     *                           {@link AmendmentLineChecks#require}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary request(UUID purchaseOrderId, long version, PurchaseOrder.AmendmentRequest request,
                                        UUID actorId) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        if (version != order.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Purchase order " + order.poNumber() + " changed since it was read; reload it");
        }
        PurchaseOrder.ProposedAmendment proposal = order.proposeAmendment(request);
        requireNoReceiptInProgress(order);
        lineChecks.require(order, proposal);

        long revisionNo = purchaseOrders.nextRevisionNo(order.id());
        UUID revisionId = purchaseOrders.recordAmendment(order, revisionNo, actorId, proposal);
        order.requestAmendment(revisionId);
        purchaseOrders.save(order);
        purchaseOrders.recordEvent(order.id(), revisionId, "REVISED", actorId, order.status(), order.status(),
                proposal.reason(), proposal.changes());

        // The requester's limit is the limit holder: an amendment raises what was approved, so it is decided again.
        Instant now = clock.instant();
        Optional<ApprovalLimit> limit = approvalLimits.autoApprovalLimit(order.id(), actorId, order.supplierId(),
                proposal.totalAmount(), now);
        if (limit.isPresent()) {
            approveAndApply(order, pending(order), actorId, limit.get(), now);
        }
        return summaryOf(order);
    }

    /**
     * Approves the amendment waiting, as the original order was approved: by someone other than who asked for it
     * (four eyes), whose own limit, when one is configured in the order's currency today, covers the new total (owner
     * decisions POLIMIT2, PO-NOLIMIT). Re-checked under the lock: the order must still be amendable and no goods
     * receipt may be in progress - the lines are about to be rewritten, and receiving counts against them.
     *
     * @throws BusinessException {@code PO_AMENDMENT_NOT_PENDING}, {@code PO_AMENDMENT_NOT_ALLOWED},
     *                           {@code SELF_APPROVAL_NOT_ALLOWED}, {@code PO_APPROVAL_LIMIT_EXCEEDED}
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary approve(UUID purchaseOrderId, UUID approverId) {
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        order.requireAmendmentDecidable();
        requireNoReceiptInProgress(order);
        PendingAmendment amendment = pending(order);
        if (approverId.equals(amendment.requestedBy())) {
            throw new BusinessException(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                    "An amendment is approved by someone other than who asked for it");
        }
        Instant now = clock.instant();
        Money newTotal = new Money(amendment.totalAmount(), order.currency());
        approvalLimits.limitExceeded(approverId, newTotal, now).ifPresent(limit -> {
            throw new BusinessException(ErrorCode.PO_APPROVAL_LIMIT_EXCEEDED,
                    "The amendment of %s makes it total %s; your approval limit is %s".formatted(order.poNumber(),
                            newTotal, limit.amount()));
        });
        approveAndApply(order, amendment, approverId, null, now);
        return summaryOf(order);
    }

    /**
     * Rejects the amendment waiting, with a reason. The order keeps its active revision, and a later amendment of it
     * is decided by a person whatever it totals ({@code hasBeenRejected}, SCRUM-124 rule 3). Possible also when the
     * supplier has refused the order meanwhile: nothing is rewritten, and the pointer must not be stranded.
     */
    @Auditable(action = AuditAction.REJECT, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary reject(UUID purchaseOrderId, UUID approverId, String reason) {
        return drop(purchaseOrderId, approverId, "REJECTED", reason);
    }

    /** Withdraws the amendment waiting, with a reason: anyone who may update the order, the requester included. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "purchase-order", resourceId = "#purchaseOrderId")
    public PurchaseOrderSummary withdraw(UUID purchaseOrderId, UUID actorId, String reason) {
        return drop(purchaseOrderId, actorId, "WITHDRAWN", reason);
    }

    // ------------------------------------------------------------------ internals

    /**
     * The amendment goes into the order: its content in SQL, then the pointers, the revision number and the supplier's
     * answer through the aggregate, reloaded so that it holds what the SQL wrote and the save does not put the old
     * content back. {@code autoLimit} is the limit that approved it automatically, or null for a person's decision.
     */
    private void approveAndApply(PurchaseOrder order, PendingAmendment amendment, UUID approverId,
                                 ApprovalLimit autoLimit, Instant now) {
        UUID previousActiveRevisionId = order.activeRevisionId();
        purchaseOrders.applyAmendment(order.id(), amendment.revisionId(), amendment.revisionNo());
        PurchaseOrder amended = loadForUpdate(order.id().value());
        SupplierConfirmationStatus answered = amended.supplierConfirmationStatus();
        Map<String, String> answerBefore = supplierAnswer(amended);
        amended.applyApprovedAmendment(amendment.revisionId(), amendment.revisionNo());
        purchaseOrders.save(amended);

        Map<String, String> facts = new LinkedHashMap<>();
        String reason = null;
        if (autoLimit == null) {
            facts.put("totalAmount", amended.totalAmount().amount().toPlainString());
            purchaseOrders.recordApproval(amendment.revisionId(), approverId, "APPROVED", null);
        } else {
            reason = "Auto-approved within limit: new total %s, limit %s".formatted(amended.totalAmount(),
                    autoLimit.amount());
            facts.putAll(autoLimit.facts(amended.totalAmount(), approverId));
            purchaseOrders.recordAutoApproval(amendment.revisionId(), approverId, approverId, autoLimit, now, reason);
        }
        // D3: the supplier agreed to the previous revision, not to this one; what they had said is kept here.
        if (amended.supplierConfirmationStatus() != answered) {
            facts.putAll(answerBefore);
        }
        purchaseOrders.recordEvent(amended.id(), amendment.revisionId(), autoLimit == null ? "APPROVED" : "AUTO_APPROVED",
                approverId, amended.status(), amended.status(), reason, facts);
        afterApproved(amended, previousActiveRevisionId, amendment);
    }

    /**
     * The supplier's answer as it stands, for the timeline when an approval sends it back to PENDING. A reference or
     * time the supplier never gave is left out rather than written as the text "null", which a reader would take for
     * a value.
     */
    private static Map<String, String> supplierAnswer(PurchaseOrder order) {
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("supplierConfirmationBefore", order.supplierConfirmationStatus().name());
        if (order.supplierReference() != null) {
            answer.put("supplierReferenceBefore", order.supplierReference());
        }
        if (order.supplierRespondedAt() != null) {
            answer.put("supplierRespondedAtBefore", order.supplierRespondedAt().toString());
        }
        return answer;
    }

    /**
     * What follows an approved amendment, by a person or automatically, once it is in the order and saved: the place
     * where a supplier that already had the order is sent the amended one (design section 8). It is given the revision
     * that was active before, so what the supplier is told can be set against what they had.
     */
    private void afterApproved(PurchaseOrder order, UUID previousActiveRevisionId,
                               PurchaseOrderRepository.PendingAmendment amendment) {
        supplierNotice.send(order, previousActiveRevisionId, amendment);
    }

    /** Rejecting and withdrawing: they change no line, so they need only an amendment waiting. */
    private PurchaseOrderSummary drop(UUID purchaseOrderId, UUID actorId, String decision, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the amendment is dropped");
        }
        PurchaseOrder order = loadForUpdate(purchaseOrderId);
        if (order.type() != PurchaseOrder.Type.STANDARD) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NOT_ALLOWED,
                    "A subcontract supplement of %s is decided as a subcontract amendment".formatted(order.poNumber()));
        }
        order.requirePendingAmendment();
        if (!AmendmentDrops.drop(purchaseOrders, order, actorId, decision, reason.trim())) {
            throw notPending(order);
        }
        purchaseOrders.save(order);
        return summaryOf(order);
    }

    /**
     * The amendment waiting. Empty also when the pending revision is a resubmission of a rejected draft (the order is
     * then PENDING_APPROVAL), which is not an amendment and is decided by {@code approve} / {@code reject} of the order.
     */
    private PendingAmendment pending(PurchaseOrder order) {
        return purchaseOrders.pendingAmendment(order.id()).orElseThrow(() -> notPending(order));
    }

    private static BusinessException notPending(PurchaseOrder order) {
        return new BusinessException(ErrorCode.PO_AMENDMENT_NOT_PENDING,
                "Purchase order %s has no amendment waiting for a decision".formatted(order.poNumber()));
    }

    /** D5: receiving is never blocked by an amendment; an amendment waits for receiving instead. */
    private void requireNoReceiptInProgress(PurchaseOrder order) {
        if (purchaseOrders.hasReceiptInProgress(order.id())) {
            throw new BusinessException(ErrorCode.PO_AMENDMENT_NOT_ALLOWED,
                    "A goods receipt of %s is being counted or waits for approval; finish it first"
                            .formatted(order.poNumber()));
        }
    }

    /** Locked, and only within the caller's warehouses when they are warehouse-bound (BR-SEC-002). */
    private PurchaseOrder loadForUpdate(UUID purchaseOrderId) {
        PurchaseOrder order = purchaseOrders.findByIdForUpdate(new PurchaseOrderId(purchaseOrderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
        WarehouseScope.requireWarehouse(order.warehouseId());
        return order;
    }

    private PurchaseOrderSummary summaryOf(PurchaseOrder order) {
        return search.summary(order.id(), false).orElseThrow();
    }
}
