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
 *   <li>DELIVERED → COMPLETED once the return window ({@code stockflow.order.return-window-days}, default 7) has passed,
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
 *       decision G3-Q1).</li>
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
        return candidates.deliveredBy(clock.instant().minus(returnWindow), skip, limit);
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
        if (order == null || !order.complete(now, returnWindow, candidates.hasOpenReturn(orderId), owing(order),
                awaitingInvoice(order))) {
            return false;
        }
        repository.save(order);
        // 4B-8 (14 §5.2): the order completed - every batch delivered, invoiced and paid - completes its contract.
        contracts.findLiveByOrderId(orderId).filter(contract -> contract.complete(now))
                .ifPresent(contract -> {
                    contracts.save(contract);
                    log.info("Sales contract {} completed with its order", contract.contractNumber());
                });
        events.publishEventsOf(order);
        log.info("Order {} completed", order.orderNumber());
        return true;
    }

    /**
     * @return whether the order was closed; never while a return of it is open (SCRUM-243): CLOSED is final (BR-05), and
     *         the return would have nowhere to put its outcome
     */
    @Transactional
    public boolean close(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || candidates.hasOpenReturn(orderId)
                || !order.close(clock.instant(), closeAfter, owing(order))) {
            return false;
        }
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} closed", order.orderNumber());
        return true;
    }
}
