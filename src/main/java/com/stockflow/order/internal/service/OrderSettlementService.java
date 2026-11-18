package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Completes and closes orders as time passes (G3-2; kltn-docs 17 §4.3 step 10, BR-05; BR-ORD-005):
 * <ul>
 *   <li>DELIVERED → COMPLETED once the return window has passed - the order's own: the days its contract agreed
 *       ({@code sales_contract.return_window_days}, Luật Thương mại Điều 318; WF-05 TH-1), else
 *       {@code stockflow.order.return-window-days}, default 7 - so an order never completes while its customer may still
 *       claim under the contract,
 *       every installment of its contract is paid (17 §4.6 step 21; 4B-10: asked of payment, the order keeps no
 *       money of its own), no return request is open, and every delivered shipment has its e-invoice issued (WF-05
 *       plan §4, condition 5: asked of invoicing, see {@link #complete}). An order whose installments still owe
 *       stays DELIVERED, and so does one with a delivery not invoiced yet.
 *       Its signed contract is completed with it, in the same transaction (4B-8, 14 §5.2).
 *       An order with an open return is RETURN_REQUESTED and so not a candidate at all; the check here stays as the
 *       guard against a return opened between the candidate query and the row lock (SCRUM-243: the return service
 *       takes the same lock, so one of the two sees the other).
 *       <br>WF-05 fix plan, section 4, keeps the four older conditions as they are - every delivery batch DELIVERED
 *       (the order is DELIVERED only then), the return window passed, no return open, every installment paid, the
 *       amounts closures and returns took off counted as settled - and adds the fifth, every delivered shipment
 *       invoiced (e-invoice ISSUED). "Order COMPLETED and contract COMPLETED" is what the workflow means by
 *       completion: there is no {@code CONTRACT_COMPLETED} status.</li>
 *   <li>RETURNED by a cancelled failed delivery → REFUNDED / PARTIALLY_REFUNDED once its refunds are made
 *       ({@link #settleFailedDelivery}; WF-05 fix plan, section 2): the order no run looked at before.</li>
 *   <li>COMPLETED / REFUNDED / PARTIALLY_REFUNDED → CLOSED after {@code stockflow.order.close-after-days} (default 30,
 *       decision G3-Q1), once nothing is owed on it either way: no installment unpaid, and - TT-4 - no refund still to
 *       make ({@code RefundProgress.outstanding}). CLOSED is final (BR-05), so a debt closed in would be out of the
 *       order's sight for good; an order refused here is tried again the next night. Completion does not wait for a
 *       refund.</li>
 *   <li>REFUNDED / PARTIALLY_REFUNDED → RETURNED when a refund record of the order is voided ({@link #refundVoided},
 *       TT-4): it waits for its refund again. Not a nightly step - the void's event moves it.</li>
 * </ul>
 * One order per transaction, through the aggregate, so each gets its history row and event, and one failing order does
 * not hold the others back.
 */
@Service
public class OrderSettlementService {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementService.class);

    private final OrderRepository repository;
    private final SettlementCandidates candidates;
    private final OrderEventPublisher events;
    private final com.stockflow.payment.api.PaymentService payments;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;
    private final DeliveryFailureService deliveryFailures;
    private final com.stockflow.order.api.DeliveryInvoices invoices;
    private final Clock clock;
    private final Duration returnWindow;
    private final Duration closeAfter;

    OrderSettlementService(OrderRepository repository, SettlementCandidates candidates, OrderEventPublisher events,
                           com.stockflow.payment.api.PaymentService payments,
                           com.stockflow.order.internal.domain.SalesContractRepository contracts,
                           DeliveryFailureService deliveryFailures,
                           com.stockflow.order.api.DeliveryInvoices invoices,
                           Clock clock, @Value("${stockflow.order.return-window-days:7}") int returnWindowDays,
                           @Value("${stockflow.order.close-after-days:30}") int closeAfterDays) {
        this.deliveryFailures = deliveryFailures;
        this.repository = repository;
        this.candidates = candidates;
        this.events = events;
        this.payments = payments;
        this.contracts = contracts;
        this.invoices = invoices;
        this.clock = clock;
        this.returnWindow = Duration.ofDays(returnWindowDays);
        this.closeAfter = Duration.ofDays(closeAfterDays);
    }

    @Transactional(readOnly = true)
    public List<UUID> completionCandidates(int skip, int limit) {
        return candidates.deliveredPastWindow(clock.instant(), (int) returnWindow.toDays(), skip, limit);
    }

    @Transactional(readOnly = true)
    public List<UUID> closingCandidates(int skip, int limit) {
        return candidates.settledBy(clock.instant().minus(closeAfter), skip, limit);
    }

    /**
     * WF-05 fix plan, section 2 (the branch that cancels what was not delivered): orders RETURNED by Sales' cancellation
     * after a failed delivery, nothing of them delivered, waiting for their refunds - what the nightly run offers
     * {@link #settleFailedDelivery}. Before this, an order RETURNED by its shipments was looked at by no run and stayed
     * RETURNED for good.
     */
    @Transactional(readOnly = true)
    public List<UUID> failedDeliveryCandidates(int skip, int limit) {
        return deliveryFailures.settlementCandidates(skip, limit);
    }

    /**
     * Such an order whose refunds are all made - none included - is REFUNDED or PARTIALLY_REFUNDED: from there
     * {@link #close} closes it after the closing delay, once it no longer owes. The refund recorded moves it at once
     * ({@code ReturnedGoodsListener}); this is for the order with nothing to refund, and for an event that was missed.
     *
     * @return whether the order moved
     */
    @Transactional
    public boolean settleFailedDelivery(UUID orderId) {
        return deliveryFailures.followRefunds(orderId, null);
    }

    /**
     * Whether the order's installments still owe something (17 §4.6 step 21), read under the order's lock - a receipt
     * locks the order first. An order without installments owes nothing in the system (4B-10).
     */
    private boolean owing(Order order) {
        var standing = payments.installmentStanding(order.id().value());
        return standing.opened() && standing.paid().compareTo(standing.total()) < 0;
    }

    /**
     * Whether a delivered shipment of the order still has no e-invoice issued (WF-05 plan §4, condition 5), read under
     * the order's lock. Invoicing answers ({@code DeliveryInvoices}); the order only knows which shipments it
     * delivered. An order delivered without shipments has nothing to invoice and waits for nothing. The rest of the
     * shipping fee, invoiced on its own when the order's undelivered rest was closed after its deliveries were all
     * invoiced, is waited for the same way - also when its draft was refused and nothing carries it yet.
     */
    private boolean awaitingInvoice(Order order) {
        List<UUID> delivered = order.shipments().stream()
                .filter(shipment -> shipment.status() == com.stockflow.order.api.OrderShipmentStatus.DELIVERED)
                .map(shipment -> shipment.id())
                .toList();
        return !delivered.isEmpty() && (!invoices.invoiced(delivered).containsAll(delivered)
                || invoices.shippingFeeUnissued(order.id().value()));
    }

    /**
     * Completes the order when all five conditions hold (WF-05 plan §4): every delivery made (the order is
     * DELIVERED), the return window passed, no return open, every installment paid, and - the fifth - every delivered
     * shipment's e-invoice issued.
     *
     * <p><b>Why the invoice is a condition.</b> COMPLETED is the order's claim that nothing is left to do, and it takes
     * the contract to COMPLETED with it; thirty days later the order is CLOSED and nothing changes it any more. A
     * delivery whose invoice is still a draft, was refused or is stuck at the provider is work left to do, with a
     * legal deadline behind it (NĐ 254/2026/NĐ-CP Điều 9 khoản 3, Phụ lục điểm 7). Without this condition the one
     * place that still shows it - an order that will not complete - would be gone. The settlement job simply finds
     * the order again on its next run, after the invoice is issued.</p>
     *
     * <p><b>Why the contract completes here, in this transaction.</b> The contract is fulfilled exactly when its order
     * is; two transactions would leave a window, and a crash between them a completed order with a contract that
     * says SIGNED for ever - no job looks for that pair again, because the order is no longer a candidate.</p>
     *
     * @return whether the order was completed
     */
    @Transactional
    public boolean complete(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        // One moment for both: the order and its contract complete together, and say so.
        java.time.Instant now = clock.instant();
        java.util.Optional<com.stockflow.order.internal.domain.SalesContract> contract = order == null
                ? java.util.Optional.empty() : contracts.findLiveByOrderId(orderId);
        // The window the contract agreed (WF-05 TH-1), the configured one when it names none.
        Duration window = contract.map(com.stockflow.order.internal.domain.SalesContract::returnWindowDays)
                .map(Duration::ofDays).orElse(returnWindow);
        if (order == null || !order.complete(now, window, candidates.hasOpenReturn(orderId), owing(order),
                awaitingInvoice(order))) {
            return false;
        }
        repository.save(order);
        // 4B-8 (14 §5.2): the order completed - every batch delivered, invoiced and paid - completes its contract.
        contract.filter(live -> live.complete(now))
                .ifPresent(done -> {
                    contracts.save(done);
                    log.info("Sales contract {} completed with its order", done.contractNumber());
                });
        events.publishEventsOf(order);
        log.info("Order {} completed", order.orderNumber());
        return true;
    }

    /**
     * Whether the seller still owes the customer a refund of the order (TT-4): something its settlements made
     * refundable is not refunded by a live record - never recorded, or voided since. Read under the order's lock:
     * recording and voiding a refund lock the order first.
     */
    private boolean refundOwed(Order order) {
        return payments.refundProgress(order.id().value()).outstanding().signum() > 0;
    }

    /**
     * @return whether the order was closed; never while a return of it is open (SCRUM-243): CLOSED is final (BR-05),
     * and the return would have nowhere to put its outcome; nor while money is owed on it either way - an installment
     * unpaid, or a refund still to make (TT-4, {@code Order.close})
     */
    @Transactional
    public boolean close(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || candidates.hasOpenReturn(orderId)
                || !order.close(clock.instant(), closeAfter, owing(order), refundOwed(order))) {
            return false;
        }
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} closed", order.orderNumber());
        return true;
    }

    /**
     * A refund record of the order was voided (TT-4; {@code OrderRefundRecorded} naming the voided record): a REFUNDED
     * or PARTIALLY_REFUNDED order goes back to RETURNED - it waits for its refund again, and the return request's or
     * the failed delivery's {@code followRefunds} moves it forward once the refund is recorded anew. Without this the
     * order stayed REFUNDED and the nightly run closed it with the refund still owed.
     *
     * <p>The void's reason goes into the status history; payment keeps it ({@code PaymentService.refundVoidReason}),
     * the event names only the record. The standing is read again under the order's lock: when the refund was recorded
     * again before this event was handled, nothing is owed and the order stays where it is. A CLOSED order is never
     * reopened (BR-05) and a CANCELLED one is final; any status but REFUNDED / PARTIALLY_REFUNDED is left alone ({@code
     * Order.refundReopened}), and the debt shows on the accountant's list of refunds still to make.</p>
     *
     * @return whether the order moved
     */
    @Transactional
    public boolean refundVoided(UUID orderId, UUID voidedRecordId, UUID by) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || !refundOwed(order) || !order.refundReopened(by, clock.instant(),
                payments.refundVoidReason(voidedRecordId).orElse(null))) {
            return false;
        }
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {}: a refund record was voided, back to {}", order.orderNumber(), order.status());
        return true;
    }
}
