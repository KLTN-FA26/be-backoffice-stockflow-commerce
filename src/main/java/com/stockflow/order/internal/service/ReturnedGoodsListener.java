package com.stockflow.order.internal.service;

import com.stockflow.contracts.OrderRefundRecorded;
import com.stockflow.contracts.ReturnedGoodsReceived;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * What other modules tell a return (team plan 4D-3) and a failed delivery (WF-05 fix plan, section 2): goods came
 * back on a confirmed return receipt ({@code procurement}, kltn-docs 03 §4.2 step 12) - a return request's, or a
 * shipment's the carrier brought back - and the accountant recorded a refund of the order ({@code payment}, 15 §4.5
 * step 16).
 *
 * <p>Events, not calls: the receipt and the refund record are another module's documents and commit on their own - the
 * goods are in quarantine whether or not the order is moved a moment later - and neither module may depend on
 * {@code order}'s return internals. {@code @ApplicationModuleListener} runs after their commit, in a transaction of its
 * own; one that fails stays in the publication registry and is delivered again, and both steps are idempotent (a
 * receipt is taken once by its id, a refund standing is read afresh under the order's lock).</p>
 *
 * <p>The handler methods are public: the transaction and the async hop are proxy advice.</p>
 */
@Component
class ReturnedGoodsListener {

    private final ReturnRequestService returns;
    private final DeliveryFailureService deliveryFailures;
    private final OrderSettlementService settlements;

    ReturnedGoodsListener(ReturnRequestService returns, DeliveryFailureService deliveryFailures,
                          OrderSettlementService settlements) {
        this.returns = returns;
        this.deliveryFailures = deliveryFailures;
        this.settlements = settlements;
    }

    @ApplicationModuleListener
    public void on(ReturnedGoodsReceived event) {
        returns.goodsReceived(event);
    }

    /**
     * A refund recorded settles a return (the order RETURNED through an RMA), or - WF-05 fix plan, section 2 - an order
     * whose failed delivery Sales cancelled: each looks at its own kind of order and leaves the other's alone.
     *
     * <p>A refund voided (TT-4) first takes a REFUNDED / PARTIALLY_REFUNDED order back to RETURNED, so that it is not
     * closed with the refund owed; the two rules after it then find it waiting, as they would have before it was
     * refunded, and move it on again only once the refund is recorded anew.</p>
     */
    @ApplicationModuleListener
    public void on(OrderRefundRecorded event) {
        if (event.voided()) {
            settlements.refundVoided(event.orderId(), event.voidedRecordId(), event.recordedBy());
        }
        returns.followRefunds(event.orderId(), event.recordedBy());
        deliveryFailures.followRefunds(event.orderId(), event.recordedBy());
    }
}
