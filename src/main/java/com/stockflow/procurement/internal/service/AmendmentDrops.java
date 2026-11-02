package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Ends the amendment waiting on a standard purchase order without applying it (SCRUM-117): rejected or withdrawn by
 * {@link PurchaseOrderAmendments}, or withdrawn because the order itself is cancelled or closed
 * ({@code ProcurementServiceImpl}). One place, so the decision row and the timeline event read the same whoever ends
 * it.
 *
 * <p>Static over the port rather than a bean: both callers already hold the repository, and the order's own service
 * is constructed by hand in unit tests that would otherwise all change for a three-line helper.</p>
 */
final class AmendmentDrops {

    private AmendmentDrops() {
    }

    /**
     * Drops the amendment waiting on {@code order}, which the caller has locked: the pointer cleared on the aggregate
     * (the caller saves it, with whatever else it changes), the decision recorded against the revision and the event
     * written, from = to = the order's status as it is now. A SUBCONTRACT order's supplement is not this one's: it is
     * decided through {@link SubcontractAmendments}.
     *
     * @param decision {@code REJECTED} or {@code WITHDRAWN}
     * @return false when no amendment of a standard order waits; nothing is written then
     */
    static boolean drop(PurchaseOrderRepository purchaseOrders, PurchaseOrder order, UUID actorId, String decision,
                        String reason) {
        if (order.type() != PurchaseOrder.Type.STANDARD || order.pendingRevisionId() == null) {
            return false;
        }
        Optional<PurchaseOrderRepository.PendingAmendment> waiting = purchaseOrders.pendingAmendment(order.id());
        if (waiting.isEmpty()) {
            return false;   // the pending revision is a resubmission waiting for approval, not an amendment
        }
        UUID revisionId = waiting.get().revisionId();
        order.dropAmendment();
        purchaseOrders.recordApproval(revisionId, actorId, decision, reason);
        purchaseOrders.recordEvent(order.id(), revisionId, decision, actorId, order.status(), order.status(), reason);
        return true;
    }
}
