package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.CompletionHoldReason;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderCompletionHoldJpaEntity;
import com.stockflow.order.internal.repository.OrderCompletionHoldJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pauses an order's automatic completion while a delivery is verified (WF-05 GH-2), and lifts the pause.
 *
 * <p><b>Why.</b> The carrier reports a delivery the customer says never arrived. Until the warehouse manager has
 * checked with the carrier - and corrected the outcome to a failure (kltn-docs 09 §4.3 step 5) or confirmed it - the
 * nightly run would complete the order as soon as the return window passed and the money was in (17 §4.6 step 21), and
 * a completed order's deliveries are no longer corrected: the invoice and the receivable could not be put right inside
 * the system any more. Sales hold the completion, with a reason; the nightly run passes the order by
 * ({@link OrderSettlementService#complete}) and finds it again every night until the hold is released.</p>
 *
 * <p><b>Why not ON_HOLD.</b> ON_HOLD is a status the order takes in place of its own (17 §4.9): set only up to PACKED,
 * it stops every batch's fulfilment, and a delivered order put ON_HOLD would no longer read as delivered - which the
 * installments after delivery count from. A completion hold changes neither the status nor any batch: it is a row
 * the completion step reads ({@code ordering.order_completion_hold}), one open per order, kept after release.</p>
 *
 * <p>Under the order's row lock, the lock the completion takes: a hold set while the night's run looks at the order
 * is either seen by it or comes after the order is completed - then refused here, as any hold of a completed order
 * is. {@code tg_order_completion_hold_respected} states the rule once more in the database.</p>
 *
 * <p><b>Every edge that finalises the order respects it</b> (WF-05 Q-17 = a, BE-05): completion, closing - also from
 * the refund branch - and every entry into the refund branch, PARTIALLY_FULFILLED or RETURNED → PARTIALLY_REFUNDED or
 * REFUNDED, by the carrier's path ({@code DeliveryFailureService.followRefunds}) and a customer's return's
 * ({@code ReturnRequestService.followReturns}) alike (audit r2 L-1, audit r3 LM-1). So a hold may be placed on any
 * order that is not final yet and has a delivery to dispute: a REFUNDED or PARTIALLY_REFUNDED order is still corrected
 * (BE-46) and still to be closed. An order with nothing delivered has nothing to verify, and the hold is refused there
 * ("nothing new"). A hold placed while a delivery stood stays when that delivery is then corrected to a failure and
 * the goods come back: the order waits RETURNED, COMPLETION_HELD among its blockers, until Sales release it - the hold
 * is their decision, never lifted by the system. A held order is never cancelled: an order with a delivered shipment
 * has no edge to CANCELLED, so no hold is left open on a cancelled order.</p>
 */
@Service
public class OrderCompletionHolds {

    private static final Logger log = LoggerFactory.getLogger(OrderCompletionHolds.class);

    /**
     * Statuses past every edge a hold keeps the order from: completed (its deliveries are no longer corrected), closed
     * or cancelled - there is nothing left to pause.
     */
    private static final Set<OrderStatus> PAST_COMPLETION = EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CLOSED,
            OrderStatus.CANCELLED);

    /** A pause of the order's completion, open while {@code releasedAt} is null. */
    public record CompletionHold(UUID id, UUID orderId, CompletionHoldReason reason, String note, Instant heldAt,
                                 UUID heldBy, Instant releasedAt, UUID releasedBy, String releaseNote) {
    }

    private final OrderRepository orders;
    private final OrderCompletionHoldJpaRepository holds;
    private final DeliveryFailureService deliveryFailures;
    private final ReturnRequestService returns;
    private final Clock clock;

    OrderCompletionHolds(OrderRepository orders, OrderCompletionHoldJpaRepository holds,
                         DeliveryFailureService deliveryFailures, ReturnRequestService returns, Clock clock) {
        this.orders = orders;
        this.holds = holds;
        this.deliveryFailures = deliveryFailures;
        this.returns = returns;
        this.clock = clock;
    }

    /** The order's open completion hold; empty when its completion is not paused. */
    @Transactional(readOnly = true)
    public Optional<CompletionHold> current(UUID orderId) {
        if (orders.findById(new OrderId(orderId)).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId);
        }
        return holds.findFirstByOrderIdAndReleasedAtIsNull(orderId).map(OrderCompletionHolds::toHold);
    }

    /**
     * Pauses the order's completion. OTHER says why in the note.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code VALIDATION_FAILED} for OTHER without a note or a note over
     *         1000 characters; {@code ORDER_COMPLETION_HOLD_NOT_APPLICABLE} for an order completed, closed or
     *         cancelled, or with nothing delivered to dispute; {@code ORDER_COMPLETION_ALREADY_HELD}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "order", resourceId = "#orderId")
    public CompletionHold hold(UUID orderId, CompletionHoldReason reason, String note, UUID by) {
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reasonCode: DELIVERY_DISPUTED or OTHER");
        }
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if ((reason == CompletionHoldReason.OTHER && trimmed == null) || (trimmed != null && trimmed.length() > 1000)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "note: what the pause is for, at most 1000 characters (required for OTHER)");
        }
        Order order = lock(orderId);
        // One rule (Order.completionHoldable), the one the order's settlement block shows the back office: past
        // completion, nothing delivered to verify, or completed once (a late claim on it is open - its deliveries are
        // no longer corrected, and the claim's end takes it back to COMPLETED, BE-40, which a hold would refuse in the
        // database).
        if (!order.completionHoldable()) {
            throw new BusinessException(ErrorCode.ORDER_COMPLETION_HOLD_NOT_APPLICABLE, PAST_COMPLETION.contains(
                    order.status()) ? "Order %s is %s: it will not be completed".formatted(order.orderNumber(),
                    order.status()) : order.completedAt() != null ? "Order %s was completed on %s".formatted(
                    order.orderNumber(), order.completedAt()) : "Order %s has no delivery to verify".formatted(
                    order.orderNumber()));
        }
        if (holds.findFirstByOrderIdAndReleasedAtIsNull(orderId).isPresent()) {
            throw new BusinessException(ErrorCode.ORDER_COMPLETION_ALREADY_HELD,
                    "The completion of order %s is paused already".formatted(order.orderNumber()));
        }
        OrderCompletionHoldJpaEntity held = holds.saveAndFlush(new OrderCompletionHoldJpaEntity(Identifiers.newId(),
                orderId, reason.name(), trimmed, now(), by));
        log.info("Order {}: completion paused ({})", order.orderNumber(), reason);
        return toHold(held);
    }

    /**
     * Lifts the pause: the next nightly run completes the order if everything else holds. A note says what the
     * verification found.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code VALIDATION_FAILED} without a note or with one over 1000
     *         characters; {@code ORDER_COMPLETION_NOT_HELD}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "order", resourceId = "#orderId")
    public CompletionHold release(UUID orderId, String note, UUID by) {
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed == null || trimmed.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "note: what the verification found, at most 1000 characters");
        }
        Order order = lock(orderId);
        OrderCompletionHoldJpaEntity open = holds.findFirstByOrderIdAndReleasedAtIsNull(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_COMPLETION_NOT_HELD,
                        "The completion of order %s is not paused".formatted(order.orderNumber())));
        open.release(by, trimmed, now());
        OrderCompletionHoldJpaEntity released = holds.saveAndFlush(open);
        log.info("Order {}: completion pause released", order.orderNumber());
        // The refund branch passed the order by while it was held (Q-17): what refunds it was waiting for may all be
        // made, and no refund record will come to move it any more - a customer's return has no nightly run to find it
        // again (audit r2 lifecycle L-1). Both paths look again now, under this lock; each moves only its own orders.
        returns.followRefunds(orderId, by);
        deliveryFailures.followRefunds(orderId, by);
        return toHold(released);
    }

    /** To the microsecond the database keeps: what a call answers is what a later read finds. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Order lock(UUID orderId) {
        return orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    private static CompletionHold toHold(OrderCompletionHoldJpaEntity row) {
        return new CompletionHold(row.getId(), row.getOrderId(), CompletionHoldReason.valueOf(row.getReason()),
                row.getNote(), row.getHeldAt(), row.getHeldBy(), row.getReleasedAt(), row.getReleasedBy(),
                row.getReleaseNote());
    }
}
