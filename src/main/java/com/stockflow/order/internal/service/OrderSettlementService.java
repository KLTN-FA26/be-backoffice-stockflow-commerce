package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.order.internal.repository.OrderHistoryNotes;
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
 *       refund. An order closing from the refund branch never passed through COMPLETED, so its closing also asks what
 *       completion asks of the goods the customer kept - every delivered shipment invoiced - and completes its contract
 *       ({@link #close}, WF-05 DĐ-1).</li>
 *   <li>REFUNDED / PARTIALLY_REFUNDED → RETURNED when a refund record of the order is voided ({@link #refundVoided},
 *       TT-4): it waits for its refund again. Not a nightly step - the void's event moves it.</li>
 *   <li>WF-05: a completion hold keeps the order from closing too (Q-17); from the refund branch it closes only once
 *       every delivered shipment's return window ended (Q-16 = b) and no shipment is still to be decided or on its way
 *       back; its contract is COMPLETED only when goods were delivered, LIQUIDATED when none were (Q-6); and the
 *       contract of an order cancelled after signing is LIQUIDATED once its money is settled
 *       ({@link #liquidateCancelled}).</li>
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
    private final SettlementReadiness readiness;
    private final OrderHistoryNotes notes;
    private final Clock clock;
    private final Duration returnWindow;
    private final Duration closeAfter;

    OrderSettlementService(OrderRepository repository, SettlementCandidates candidates, OrderEventPublisher events,
                           com.stockflow.payment.api.PaymentService payments,
                           com.stockflow.order.internal.domain.SalesContractRepository contracts,
                           DeliveryFailureService deliveryFailures, SettlementReadiness readiness,
                           OrderHistoryNotes notes, Clock clock,
                           @Value("${stockflow.order.return-window-days:7}") int returnWindowDays,
                           @Value("${stockflow.order.close-after-days:30}") int closeAfterDays) {
        this.deliveryFailures = deliveryFailures;
        this.repository = repository;
        this.candidates = candidates;
        this.events = events;
        this.payments = payments;
        this.contracts = contracts;
        this.readiness = readiness;
        this.notes = notes;
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

    /** What the order's detail shows staff of its settlement (F-1): when it was refunded, and what it waits for. */
    public record Readiness(java.time.Instant refundedAt, SettlementReadiness.View view) {
    }

    /** F-1 (BE-34): the conditions {@link #complete} and {@link #close} decide by, as they stand now; empty: no order. */
    @Transactional(readOnly = true)
    public java.util.Optional<Readiness> readiness(UUID orderId) {
        return repository.findById(new OrderId(orderId))
                .map(order -> new Readiness(order.refundedAt(), readiness.view(order)));
    }

    /** A refund record voided while a claim of the order was open (BE-52), as its history lists it. */
    public record RefundVoid(String reason, UUID voidedBy, java.time.Instant voidedAt) {
    }

    /** The refund voids of the order that moved no status (BE-52), oldest first, for its history. */
    @Transactional(readOnly = true)
    public List<RefundVoid> refundVoids(UUID orderId) {
        return notes.refundVoids(orderId).stream()
                .map(note -> new RefundVoid(note.reason(), note.voidedBy(), note.voidedAt())).toList();
    }

    /** WF-05 Q-6: cancelled orders whose contract is still SIGNED - what {@link #liquidateCancelled} is offered. */
    @Transactional(readOnly = true)
    public List<UUID> liquidationCandidates(int skip, int limit) {
        return candidates.cancelledWithSignedContract(skip, limit);
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
     * Such an order whose refunds are all made is REFUNDED or PARTIALLY_REFUNDED: from there {@link #close} closes it
     * after the closing delay, once it no longer owes. The refund recorded moves it at once ({@code
     * ReturnedGoodsListener}); this is for an event that was missed, and for an order a completion hold kept back. One
     * with nothing made refundable does not move (WF-05 BE-45): {@link #close} closes it from where it is.
     *
     * @return whether the order moved
     */
    @Transactional
    public boolean settleFailedDelivery(UUID orderId) {
        return deliveryFailures.followRefunds(orderId, null);
    }

    /**
     * Completes the order when all five conditions hold (WF-05 plan §4): every delivery made (the order is
     * DELIVERED), the return window passed, no return open, every installment paid, and - the fifth - every delivered
     * shipment's e-invoice issued. And not while Sales hold its completion (WF-05 GH-2). The conditions are read through
     * {@link SettlementReadiness}, the one source the order's detail shows them from (BE-34).
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
        if (order == null) {
            return false;
        }
        // One moment for both: the order and its contract complete together, and say so.
        java.time.Instant now = clock.instant();
        java.util.Optional<com.stockflow.order.internal.domain.SalesContract> contract =
                contracts.findLiveByOrderId(orderId);
        SettlementReadiness.Facts facts = readiness.facts(order, contract);
        // WF-05 GH-2: Sales paused the completion while a delivery is verified (OrderCompletionHolds) - completed, the
        // delivery could no longer be corrected. Passed by tonight; found again once the hold is released.
        if (facts.held() || !order.complete(now, facts.window(), facts.openReturn(), facts.owing(),
                facts.awaitingInvoice())) {
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
     * Closes the order once its closing delay has passed.
     *
     * <p><b>From the refund branch</b> (WF-05 DĐ-1; 17 §4.6 step 22, §5.1; 14 §5.2). An order delivered in full and then
     * partly returned goes DELIVERED → RETURN_REQUESTED → RETURNED → REFUNDED / PARTIALLY_REFUNDED → CLOSED and never
     * passes through COMPLETED - the one step that checked the goods the customer kept were invoiced and completed the
     * contract. So closing a REFUNDED / PARTIALLY_REFUNDED order asks the same of it: every delivered shipment has its
     * e-invoice issued - otherwise it stays open and the next night asks again, as completion does - and every
     * delivered shipment's return window has ended (WF-05 Q-16 = b, BE-19: the closing delay alone let a 60-day
     * contract window close at day 30, and BR-05 then refused a claim the contract still granted). Its signed contract
     * ends with it in this transaction, for the reason {@link #complete} gives: COMPLETED when goods of the order were
     * delivered, LIQUIDATED when none were - every shipment came back or was lost (WF-05 Q-6, BE-06). A return's
     * decrease adjustment not issued yet does not keep it open (Q-16): it lowers the tax declared, and is still issued,
     * and reminded of, after the order closed.</p>
     *
     * <p><b>Without a refund</b> (WF-05 Q-4 / BE-45; audit r2 contract M-6). An order the carrier's returns or losses
     * ended whose settlements made nothing refundable stays RETURNED / PARTIALLY_FULFILLED - nothing was refunded, so it
     * is never "Hoàn một phần" - and closes from there ({@code Order.closeWithoutRefund}) on the refund branch's terms:
     * the closing delay from when its deliveries ended, every delivered shipment invoiced and past its window, the fee
     * rest invoiced, nothing owed, no hold; its contract COMPLETED or LIQUIDATED likewise.</p>
     *
     * @return whether the order was closed; never while a return of it is open (SCRUM-243): CLOSED is final (BR-05),
     * and the return would have nowhere to put its outcome; nor while money is owed on it either way - an installment
     * unpaid, or a refund still to make (TT-4, {@code Order.close}); nor while Sales hold its completion (Q-17); nor
     * while a shipment of it is still to be decided or its return receipt is not confirmed; nor, from the refund branch,
     * while a delivered shipment has no e-invoice issued or a return window is open
     */
    @Transactional
    public boolean close(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return false;
        }
        boolean fromRefund = order.status() == com.stockflow.order.api.OrderStatus.REFUNDED
                || order.status() == com.stockflow.order.api.OrderStatus.PARTIALLY_REFUNDED;
        // WF-05 Q-4 / BE-45 (audit r2 contract M-6): an order the carrier's returns or losses ended with nothing made
        // refundable never entered the refund branch; it closes from RETURNED / PARTIALLY_FULFILLED on the same terms.
        boolean withoutRefund = !fromRefund && order.endedByCarrier();
        java.util.Optional<com.stockflow.order.internal.domain.SalesContract> contract =
                contracts.findLiveByOrderId(orderId);
        SettlementReadiness.Facts facts = readiness.facts(order, contract);
        boolean asRefundBranch = fromRefund || withoutRefund;
        if (facts.openReturn() || asRefundBranch && facts.awaitingInvoice()) {
            return false;
        }
        boolean closed = withoutRefund
                ? order.closeWithoutRefund(clock.instant(), closeAfter, facts.owing(),
                        payments.refundProgress(orderId).refundable().signum() > 0, facts.held(),
                        facts.windowsCloseAt())
                : order.close(clock.instant(), closeAfter, facts.owing(), facts.refundOwed(), facts.held(),
                        fromRefund ? facts.windowsCloseAt() : null);
        if (!closed) {
            return false;
        }
        repository.save(order);
        if (asRefundBranch) {
            contract.filter(live -> live.status() == com.stockflow.order.internal.domain.SalesContractStatus.SIGNED)
                    .ifPresent(live -> endContract(live, order, order.closedAt()));
        }
        events.publishEventsOf(order);
        log.info("Order {} closed", order.orderNumber());
        return true;
    }

    /**
     * WF-05 Q-6 (BE-06): an order cancelled after its contract was signed left the contract SIGNED for ever - no path
     * ended it. Once the cancellation's money is settled - nothing owed on its installments (a fee item paid) and
     * nothing it made refundable still to refund - the contract is LIQUIDATED. Nothing was delivered: an order with a
     * delivered shipment has no edge to CANCELLED. Asked each night until the money is settled.
     *
     * @return whether the contract was liquidated
     */
    @Transactional
    public boolean liquidateCancelled(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || order.status() != com.stockflow.order.api.OrderStatus.CANCELLED
                || order.deliveredSomething()) {
            return false;
        }
        var contract = contracts.findLiveByOrderId(orderId)
                .filter(live -> live.status() == com.stockflow.order.internal.domain.SalesContractStatus.SIGNED);
        if (contract.isEmpty()) {
            return false;
        }
        var standing = payments.installmentStanding(orderId);
        boolean owing = standing.opened() && standing.paid().compareTo(standing.total()) < 0;
        if (owing || payments.refundProgress(orderId).outstanding().signum() > 0) {
            return false;
        }
        endContract(contract.get(), order, clock.instant());
        return true;
    }

    /**
     * Ends a signed contract with its order (Q-6): COMPLETED when goods were delivered, LIQUIDATED when none were -
     * the database checks the same ({@code check_sales_contract_transition}). A liquidation is announced
     * ({@code SalesContractStatusChanged}, by nobody: the nightly run), so the chat's contract card closes.
     */
    private void endContract(com.stockflow.order.internal.domain.SalesContract live, Order order,
                             java.time.Instant at) {
        if (order.deliveredSomething()) {
            if (live.complete(at)) {
                contracts.save(live);
                log.info("Sales contract {} completed with its order", live.contractNumber());
            }
            return;
        }
        if (live.liquidate(at)) {
            contracts.save(live);
            events.publish(new com.stockflow.contracts.SalesContractStatusChanged(live.id(), live.contractNumber(),
                    order.id().value(), order.orderNumber().value(), order.customerId(), live.status().name(), null,
                    at));
            log.info("Sales contract {} liquidated: its order {} delivered nothing", live.contractNumber(),
                    order.orderNumber());
        }
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
     * reopened (BR-05) and a CANCELLED one is final; the debt shows on the accountant's list of refunds still to
     * make.</p>
     *
     * <p>WF-05 BE-52: a void while a further claim of the order is open, or its goods are back, moves no status - the
     * claim decides - so no status row would say it happened. It is written into the order's history as an entry of its
     * own ({@code REFUND_VOIDED}, {@link OrderHistoryNotes}), and the claim, should it end without money, takes the order
     * back to waiting for the refund rather than to refunded ({@code Order.refundReopened}).</p>
     *
     * @return whether the order's status moved
     */
    @Transactional
    public boolean refundVoided(UUID orderId, UUID voidedRecordId, UUID by) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || payments.refundProgress(orderId).outstanding().signum() <= 0) {
            return false;
        }
        com.stockflow.order.api.OrderStatus before = order.status();
        String reason = payments.refundVoidReason(voidedRecordId).orElse(null);
        java.time.Instant now = clock.instant();
        boolean changed = order.refundReopened(by, now, reason);
        if (before == com.stockflow.order.api.OrderStatus.RETURN_REQUESTED
                || before == com.stockflow.order.api.OrderStatus.RETURNED) {
            // On the return path the claim decides where the order goes: no status row says the void happened.
            notes.refundVoided(orderId, voidedRecordId, reason, by, now);
            log.info("Order {}: a refund record was voided while a claim is open; it stays {}", order.orderNumber(),
                    before);
        }
        if (!changed) {
            return false;
        }
        repository.save(order);
        if (order.status() == before) {
            return false;
        }
        events.publishEventsOf(order);
        log.info("Order {}: a refund record was voided, back to {}", order.orderNumber(), order.status());
        return true;
    }
}
