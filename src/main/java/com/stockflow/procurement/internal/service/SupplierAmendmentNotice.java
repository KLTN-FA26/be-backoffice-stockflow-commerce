package com.stockflow.procurement.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository.PendingAmendment;
import com.stockflow.procurement.internal.domain.RevisionDiff;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tells the supplier of an order that an amendment was approved (SCRUM-117, design section 8): the order as it now
 * stands and what changed against the revision they had, published as a {@link PurchaseOrderSent} under a delivery
 * reference of its own. It joins the caller's transaction, so the notice exists exactly when the approval does.
 *
 * <p>One signature for the STANDARD path ({@code PurchaseOrderAmendments}) and the SUBCONTRACT one
 * ({@code SubcontractPurchasingImpl}); both hold the order locked and reload it after the amendment was written.</p>
 *
 * <p>The order's own delivery, if it has not reached the supplier yet, is superseded first
 * ({@link NotificationService#supersedeSupplierDelivery}): the notice carries the whole order as amended, and the
 * order as it was, arriving after it from a retry, would undo it in the supplier's eyes.</p>
 */
@Component
class SupplierAmendmentNotice {

    private final PurchaseOrderRepository purchaseOrders;
    private final PoDeliveryDecisionRepository deliveryDecisions;
    private final PoDeliveryContent content;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    SupplierAmendmentNotice(PurchaseOrderRepository purchaseOrders, PoDeliveryDecisionRepository deliveryDecisions,
                            PoDeliveryContent content, NotificationService notifications,
                            ApplicationEventPublisher events) {
        this.purchaseOrders = purchaseOrders;
        this.deliveryDecisions = deliveryDecisions;
        this.content = content;
        this.notifications = notifications;
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
        var buyer = content.buyer(deliveryDecisions.latestSnapshot(order.id().value()));
        notifications.supersedeSupplierDelivery(order.id().value(), target.channel(), target.recipient());
        events.publishEvent(new PurchaseOrderSent(order.id().value(), order.poNumber(), order.supplierId(),
                target.channel(), target.recipient(), order.totalAmount().amount(),
                order.currency().getCurrencyCode(), order.expectedAt(), order.paymentTermDays(), content.lines(order),
                0, buyer, false, null, Math.toIntExact(amendment.revisionNo()), amendment.reason(),
                changes(previousActiveRevisionId, amendment.revisionId())));
    }

    /**
     * What changed against the revision the supplier had: the header fields first (the expected date, the note), then
     * line by line - the same {@link RevisionDiff} the revision history shows. Field codes, not words: the API channel
     * passes them on as they are, and the e-mail puts them into the supplier's language.
     */
    private List<PurchaseOrderSent.Change> changes(UUID previousRevisionId, UUID amendmentRevisionId) {
        List<PurchaseOrderSent.Change> changes = new ArrayList<>();
        RevisionDiff.header(purchaseOrders.revisionHeader(previousRevisionId),
                        purchaseOrders.revisionHeader(amendmentRevisionId))
                .forEach(change -> changes.add(new PurchaseOrderSent.Change(null, null, change.field(),
                        change.before(), change.after())));
        RevisionDiff.between(purchaseOrders.revisionLines(previousRevisionId),
                        purchaseOrders.revisionLines(amendmentRevisionId))
                .forEach(change -> changes.add(new PurchaseOrderSent.Change(change.lineNo(), change.sku(),
                        change.field(), change.before(), change.after())));
        return changes;
    }
}
