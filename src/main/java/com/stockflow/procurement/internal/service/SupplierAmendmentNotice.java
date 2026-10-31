package com.stockflow.procurement.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.PendingAmendment;
import com.stockflow.procurement.internal.domain.RevisionDiff;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells the supplier of an order that an amendment was approved (SCRUM-117, design section 8): the order as it now
 * stands and what changed against the revision they had, published as a {@link PurchaseOrderSent} under a delivery
 * reference of its own. It joins the caller's transaction, so the notice exists exactly when the approval does.
 *
 * <p>One signature for the STANDARD path ({@code PurchaseOrderAmendments}) and the SUBCONTRACT one
 * ({@code SubcontractPurchasingImpl}); both hold the order locked and reload it after the amendment was written.</p>
 */
@Component
class SupplierAmendmentNotice {

    private final PurchaseOrderRepository purchaseOrders;
    private final PoDeliveryDecisionRepository deliveryDecisions;
    private final PoDeliveryContent content;
    private final ApplicationEventPublisher events;

    SupplierAmendmentNotice(PurchaseOrderRepository purchaseOrders, PoDeliveryDecisionRepository deliveryDecisions,
                            PoDeliveryContent content, ApplicationEventPublisher events) {
        this.purchaseOrders = purchaseOrders;
        this.deliveryDecisions = deliveryDecisions;
        this.content = content;
        this.events = events;
    }

    /**
     * Publishes the notice when the supplier had the order ({@code confirmedAt} set) and has not refused it; an order
     * only approved has not been seen by them, and confirming it later sends its current content.
     *
     * @param order                    the order after the amendment was applied, reloaded
     * @param previousActiveRevisionId the revision that was active before: what the changes are set against
     * @param amendment                the amendment just approved
     */
    void send(PurchaseOrder order, UUID previousActiveRevisionId, PendingAmendment amendment) {
        if (order.confirmedAt() == null || order.supplierConfirmationStatus() == SupplierConfirmationStatus.REJECTED) {
            return;
        }
        var target = content.target(order.supplierId());
        var changes = RevisionDiff.between(purchaseOrders.revisionLines(previousActiveRevisionId),
                        purchaseOrders.revisionLines(amendment.revisionId())).stream()
                .map(change -> new PurchaseOrderSent.Change(change.lineNo(), change.sku(), change.field(),
                        change.before(), change.after()))
                .toList();
        var buyer = content.buyer(deliveryDecisions.latestSnapshot(order.id().value()));
        events.publishEvent(new PurchaseOrderSent(order.id().value(), order.poNumber(), order.supplierId(),
                target.channel(), target.recipient(), order.totalAmount().amount(),
                order.currency().getCurrencyCode(), order.expectedAt(), order.paymentTermDays(), content.lines(order),
                0, buyer, false, null, Math.toIntExact(amendment.revisionNo()), amendment.reason(), changes));
    }
}
