package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.payment.api.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What an order still waits for before it completes or closes (WF-05 BE-34, contract F-1), read once for both: the
 * nightly settlement decides by these facts ({@link OrderSettlementService#complete}, {@link OrderSettlementService#close})
 * and {@code GET /orders/{id}} shows them ({@link #view}). Before this, the back office worked the conditions out on
 * its own - a fixed seven-day window, no shipping fee, no refund branch - and told staff something the job did not do.
 * One source, so the two cannot drift apart again.
 *
 * <p>Read in the caller's transaction: the settlement asks under the order's row lock, which a receipt, a refund
 * record, a return and a hold take too.</p>
 */
@Component
public class SettlementReadiness {

    /** What keeps the order from completing or closing, as the back office labels it. */
    public enum Blocker {
        NOT_DELIVERED, RETURN_WINDOW_OPEN, OWING, OPEN_RETURN, SHIPMENT_UNINVOICED, SHIPPING_FEE_UNINVOICED,
        REFUND_OUTSTANDING, COMPLETION_HELD
    }

    /** Which step the order waits for: completion (DELIVERED), closing from the refund branch, or neither. */
    public enum Branch { COMPLETION, REFUND_CLOSE, NONE }

    /**
     * The facts both steps decide by.
     *
     * @param window         the return window: the contract's days (TH-1), else the configured default
     * @param windowsCloseAt when the return window of the order's latest delivery ends - every delivered shipment's
     *                       window has ended then (Q-16 = b); null when nothing was delivered
     * @param uninvoiced     the DELIVERED shipments without an issued e-invoice
     * @param shippingFeeUninvoiced the rest of the order's fees - its shipping fee's rest and any plate fee no invoice
     *                       carries (WF-05 BE-62, Q-19) - is not on an issued invoice: asked whatever was delivered,
     *                       since an order that delivered nothing still owes its plate fee, invoiced
     * @param deliveryReductionUnissued a reduction for a delivery that did not happen is drawn up and not issued (audit
     *                       r3 EINV-7, {@code DeliveryInvoices#deliveryReductionUnissued}): information only, as
     *                       {@code returnAdjustmentUnissued} (Q-16) - the order may close and its contract be liquidated
     *                       meanwhile, and the block says what the accountant still has to issue
     * @param failedDeliveryUnreduced the order's shipments that are not delivered now - corrected to a failure, or
     *                       brought back - whose issued invoice still stands, with no reduction of it drawn up (BE #423,
     *                       audit 25/11 N-1): the system could not reduce it (a correction of it at the provider, an
     *                       adjustment it cannot take off, a replacement, Q-9), so the buyer holds an invoice for goods
     *                       it never received and nothing said so. Information only, as {@code deliveryReductionUnissued}:
     *                       a decrease has no deadline (TT 91/2026 Điều 10 khoản 5 điểm c.1) and can be made after the
     *                       order closes - by "Lập bản giảm do giao thất bại" or on the provider's portal (Q-8) - but the
     *                       block names it until it is
     */
    public record Facts(Duration window, Instant windowsCloseAt, boolean openReturn, boolean held, boolean owing,
                        boolean refundOwed, List<UUID> uninvoiced, boolean shippingFeeUninvoiced,
                        boolean returnAdjustmentUnissued, boolean deliveryReductionUnissued,
                        List<UUID> failedDeliveryUnreduced) {

        /** A delivery of the order, or the rest of its shipping fee, has no issued e-invoice (WF-05 plan §4, cond. 5). */
        public boolean awaitingInvoice() {
            return !uninvoiced.isEmpty() || shippingFeeUninvoiced;
        }
    }

    /**
     * F-1, as {@code OrderResponse.settlementReadiness} shows it.
     *
     * @param shippingFeeUninvoiced as {@link Facts#shippingFeeUninvoiced}: the fee rest, the plate fee included
     *                    (blocker {@code SHIPPING_FEE_UNINVOICED}); the name is kept so the back office reads it as before
     * @param completionHoldable whether Sales may pause the order's completion now ({@code Order.completionHoldable},
     *                    the rule {@code OrderCompletionHolds.hold} applies) and it is not paused already - the one
     *                    flag the back office offers "Giữ hoàn tất" by (audit r2 lifecycle M-4)
     * @param deliveryReductionUnissued as {@link Facts#deliveryReductionUnissued}: information only, never a blocker
     * @param failedDeliveryUnreducedShipmentIds as {@link Facts#failedDeliveryUnreduced}: information only, never a
     *                    blocker
     *
     * @param completesAt when the order completes as far as time goes (COMPLETION): its return window's end
     * @param closesAt    when it closes as far as time goes: the closing delay after its completion or refund, and -
     *                    from the refund branch - not before every delivered shipment's return window ended
     */
    public record View(Branch branch, Integer returnWindowDays, Instant returnWindowEndsAt, boolean owing,
                       boolean openReturn, List<UUID> uninvoicedShipmentIds, boolean shippingFeeUninvoiced,
                       boolean refundOutstanding, boolean completionHeld, boolean returnAdjustmentUnissued,
                       Instant completesAt, Instant closesAt, List<Blocker> blockers, boolean completionHoldable,
                       boolean deliveryReductionUnissued, List<UUID> failedDeliveryUnreducedShipmentIds) {
    }

    private final SettlementCandidates candidates;
    private final PaymentService payments;
    private final SalesContractRepository contracts;
    private final DeliveryInvoices invoices;
    private final Duration defaultWindow;
    private final Duration closeAfter;
    private final java.time.Clock clock;

    SettlementReadiness(SettlementCandidates candidates, PaymentService payments, SalesContractRepository contracts,
                        DeliveryInvoices invoices, java.time.Clock clock,
                        @Value("${stockflow.order.return-window-days:7}") int returnWindowDays,
                        @Value("${stockflow.order.close-after-days:30}") int closeAfterDays) {
        this.candidates = candidates;
        this.payments = payments;
        this.contracts = contracts;
        this.invoices = invoices;
        this.clock = clock;
        this.defaultWindow = Duration.ofDays(returnWindowDays);
        this.closeAfter = Duration.ofDays(closeAfterDays);
    }

    /** The closing delay (G3-Q1). */
    Duration closeAfter() {
        return closeAfter;
    }

    /** The order's facts, its live contract read here. */
    public Facts facts(Order order) {
        return facts(order, contracts.findLiveByOrderId(order.id().value()));
    }

    /** The order's facts, with its live contract as the caller read it. */
    public Facts facts(Order order, Optional<SalesContract> contract) {
        UUID orderId = order.id().value();
        Duration window = contract.map(SalesContract::returnWindowDays).map(Duration::ofDays).orElse(defaultWindow);
        Instant lastDelivery = order.lastDeliveryAt().orElse(order.shipments().isEmpty() ? order.deliveredAt() : null);
        Instant windowsCloseAt = lastDelivery == null ? null : ReturnPolicy.endOfDays(lastDelivery, window);
        List<UUID> delivered = order.shipments().stream()
                .filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERED)
                .map(shipment -> shipment.id()).toList();
        List<UUID> uninvoiced = new ArrayList<>();
        if (!delivered.isEmpty()) {
            Set<UUID> invoiced = invoices.invoiced(delivered);
            delivered.stream().filter(id -> !invoiced.contains(id)).forEach(uninvoiced::add);
        }
        // Whatever was delivered (audit r2 lifecycle M-1 / money M-2): an order whose goods all came back or were lost
        // keeps its plate fee (Q-19), and that fee is invoiced on the rest invoice (BE-62) - before, the question was
        // asked only when something was delivered, and such an order closed, its contract LIQUIDATED, with the fee
        // collected and its invoice a draft, refused or never drawn up.
        boolean feeUninvoiced = invoices.shippingFeeUnissued(orderId);
        var standing = payments.installmentStanding(orderId);
        boolean owing = standing.opened() && standing.paid().compareTo(standing.total()) < 0;
        boolean refundOwed = payments.refundProgress(orderId).outstanding().signum() > 0;
        // BE #423 / N-1: an issued invoice still standing for a shipment that is not delivered now. `invoiced` leaves
        // out an invoice reversed, or with its reduction drawn up (deliveryReductionUnissued says that one).
        List<UUID> undelivered = order.shipments().stream()
                .filter(shipment -> shipment.status() == OrderShipmentStatus.DELIVERY_FAILED
                        || shipment.status() == OrderShipmentStatus.RETURNED)
                .map(shipment -> shipment.id()).toList();
        List<UUID> unreduced = undelivered.isEmpty() ? List.of()
                : undelivered.stream().filter(invoices.invoiced(undelivered)::contains).toList();
        return new Facts(window, windowsCloseAt, candidates.hasOpenReturn(orderId), candidates.completionHeld(orderId),
                owing, refundOwed, List.copyOf(uninvoiced), feeUninvoiced, invoices.returnAdjustmentUnissued(orderId),
                invoices.deliveryReductionUnissued(orderId), unreduced);
    }

    /**
     * F-1: null while nothing of the order was delivered and it is not ending either - there is nothing to complete or
     * close yet. An order whose goods all came back or were lost is ending (audit r2 lifecycle M-1): from the refund
     * branch, or ended by the carrier with nothing made refundable (BE-45), it closes, and the block says what it waits
     * for - the fee rest's invoice among it. The blockers are those of the step the order waits for; a COMPLETED order
     * waits for closing only by time, money, an open return and holds, and lists those blockers with branch NONE.
     */
    public View view(Order order) {
        boolean anythingDelivered = order.deliveredAt() != null || order.lastDeliveryAt().isPresent();
        boolean refundBranch = order.status() == OrderStatus.REFUNDED
                || order.status() == OrderStatus.PARTIALLY_REFUNDED;
        boolean endedByCarrier = order.endedByCarrier();
        if (!anythingDelivered && !refundBranch && !endedByCarrier) {
            return null;
        }
        Optional<SalesContract> contract = contracts.findLiveByOrderId(order.id().value());
        Facts facts = facts(order, contract);
        Instant now = clock.instant();
        OrderStatus status = order.status();
        // A claim open waits on the step of the status it was opened from (BE-40): a completed order's claim does not
        // make it complete again.
        OrderStatus waitsAs = status == OrderStatus.RETURN_REQUESTED && order.statusBeforeReturn() != null
                ? order.statusBeforeReturn() : status;
        Branch branch = switch (waitsAs) {
            case DELIVERED, RETURN_REQUESTED -> Branch.COMPLETION;
            case REFUNDED, PARTIALLY_REFUNDED -> Branch.REFUND_CLOSE;
            // BE-45: ended by the carrier, it closes as the refund branch does once nothing is left to refund.
            case RETURNED, PARTIALLY_FULFILLED -> endedByCarrier ? Branch.REFUND_CLOSE : Branch.NONE;
            default -> Branch.NONE;
        };
        List<Blocker> blockers = new ArrayList<>();
        Instant completesAt = null;
        Instant closesAt = null;
        if (branch == Branch.COMPLETION) {
            completesAt = order.deliveredAt() == null ? null : ReturnPolicy.endOfDays(order.deliveredAt(),
                    facts.window());
            if (order.deliveredAt() == null) {
                blockers.add(Blocker.NOT_DELIVERED);
            } else if (completesAt.isAfter(now)) {
                blockers.add(Blocker.RETURN_WINDOW_OPEN);
            }
        } else if (branch == Branch.REFUND_CLOSE) {
            // From its refund - also a claim opened from the refund branch (BE-40) - or, ended by the carrier with
            // nothing refunded (BE-45), from when its deliveries ended.
            Instant since = waitsAs == OrderStatus.REFUNDED || waitsAs == OrderStatus.PARTIALLY_REFUNDED
                    ? order.refundedAt() : order.deliveriesEndedAt().orElse(null);
            closesAt = later(since == null ? null : since.plus(closeAfter), facts.windowsCloseAt());
            if (facts.windowsCloseAt() != null && facts.windowsCloseAt().isAfter(now)) {
                blockers.add(Blocker.RETURN_WINDOW_OPEN);
            }
            if (!order.deliveriesSettled()) {
                blockers.add(Blocker.NOT_DELIVERED);
            }
        } else if (waitsAs == OrderStatus.COMPLETED && order.completedAt() != null) {
            closesAt = order.completedAt().plus(closeAfter);
        }
        if (branch != Branch.NONE || waitsAs == OrderStatus.COMPLETED) {
            if (facts.owing()) {
                blockers.add(Blocker.OWING);
            }
            if (facts.openReturn()) {
                blockers.add(Blocker.OPEN_RETURN);
            }
            if (branch != Branch.NONE && !facts.uninvoiced().isEmpty()) {
                blockers.add(Blocker.SHIPMENT_UNINVOICED);
            }
            if (branch != Branch.NONE && facts.shippingFeeUninvoiced()) {
                blockers.add(Blocker.SHIPPING_FEE_UNINVOICED);
            }
            if (branch != Branch.COMPLETION && facts.refundOwed()) {
                blockers.add(Blocker.REFUND_OUTSTANDING);
            }
            if (facts.held()) {
                blockers.add(Blocker.COMPLETION_HELD);
            }
        }
        Integer days = (int) facts.window().toDays();
        return new View(branch, days, facts.windowsCloseAt(), facts.owing(), facts.openReturn(), facts.uninvoiced(),
                facts.shippingFeeUninvoiced(), facts.refundOwed(), facts.held(), facts.returnAdjustmentUnissued(),
                completesAt, closesAt, List.copyOf(blockers), !facts.held() && order.completionHoldable(),
                facts.deliveryReductionUnissued(), facts.failedDeliveryUnreduced());
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
