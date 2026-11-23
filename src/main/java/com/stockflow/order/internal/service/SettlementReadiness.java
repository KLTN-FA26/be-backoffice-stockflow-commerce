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
     */
    public record Facts(Duration window, Instant windowsCloseAt, boolean openReturn, boolean held, boolean owing,
                        boolean refundOwed, List<UUID> uninvoiced, boolean shippingFeeUninvoiced,
                        boolean returnAdjustmentUnissued) {

        /** A delivery of the order, or the rest of its shipping fee, has no issued e-invoice (WF-05 plan §4, cond. 5). */
        public boolean awaitingInvoice() {
            return !uninvoiced.isEmpty() || shippingFeeUninvoiced;
        }
    }

    /**
     * F-1, as {@code OrderResponse.settlementReadiness} shows it.
     *
     * @param completesAt when the order completes as far as time goes (COMPLETION): its return window's end
     * @param closesAt    when it closes as far as time goes: the closing delay after its completion or refund, and -
     *                    from the refund branch - not before every delivered shipment's return window ended
     */
    public record View(Branch branch, Integer returnWindowDays, Instant returnWindowEndsAt, boolean owing,
                       boolean openReturn, List<UUID> uninvoicedShipmentIds, boolean shippingFeeUninvoiced,
                       boolean refundOutstanding, boolean completionHeld, boolean returnAdjustmentUnissued,
                       Instant completesAt, Instant closesAt, List<Blocker> blockers) {
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
        boolean feeUninvoiced = false;
        if (!delivered.isEmpty()) {
            Set<UUID> invoiced = invoices.invoiced(delivered);
            delivered.stream().filter(id -> !invoiced.contains(id)).forEach(uninvoiced::add);
            feeUninvoiced = invoices.shippingFeeUnissued(orderId);
        }
        var standing = payments.installmentStanding(orderId);
        boolean owing = standing.opened() && standing.paid().compareTo(standing.total()) < 0;
        boolean refundOwed = payments.refundProgress(orderId).outstanding().signum() > 0;
        return new Facts(window, windowsCloseAt, candidates.hasOpenReturn(orderId), candidates.completionHeld(orderId),
                owing, refundOwed, List.copyOf(uninvoiced), feeUninvoiced, invoices.returnAdjustmentUnissued(orderId));
    }

    /**
     * F-1: null before anything of the order was delivered - there is nothing to complete or close yet. The blockers
     * are those of the step the order waits for; a COMPLETED order waits for closing only by time, money and holds.
     */
    public View view(Order order) {
        boolean anythingDelivered = order.deliveredAt() != null || order.lastDeliveryAt().isPresent();
        if (!anythingDelivered) {
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
            closesAt = later(order.refundedAt() == null ? null : order.refundedAt().plus(closeAfter),
                    facts.windowsCloseAt());
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
                completesAt, closesAt, List.copyOf(blockers));
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
