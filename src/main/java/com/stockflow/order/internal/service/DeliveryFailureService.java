package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.DeliveryFailureCase;
import com.stockflow.order.internal.domain.DeliveryFailureCases;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.payment.api.ChargeFailedDeliveryCommand;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivery failure cases (WF-05 fix plan, sections 2-3; kltn-docs 09 §4.3, 03 §4.2, 17 §4.8, 15 §4.6): what happens to
 * an order after the carrier brought one of its shipments back.
 *
 * <pre>
 *   carrier reports it back ─▶ shipment RETURNED ─▶ order ON_HOLD (DELIVERY_FAILED)     (OrderServiceImpl)
 *   return receipt confirmed ─▶ case OPEN, cartons in quarantine                         ({@link #goodsReceived})
 *   Sales decide ─┬─▶ deliver again: hold lifted, goods held as they are put away,       ({@link #redeliver})
 *                 │    released, picked, checked, handed over ─▶ case REDELIVERED
 *                 └─▶ cancel what was not delivered: the rest closed with its fee,        ({@link #cancelUndelivered})
 *                      what was collected above goods delivered + fee refundable
 * </pre>
 *
 * <p>The order is held from the shipment's outcome, not from the receipt: the customer is owed the goods from the
 * moment they failed to arrive, and the receipt - which needs the shipment RETURNED - only tells Sales what came back
 * and in what state. Nothing here refunds by default: the goods are the customer's own SKU, sold to nobody else.</p>
 *
 * <p>Locks: the order's row first, every time, then what payment and inventory lock inside their own calls (orders,
 * then installments; SKU advisory locks, sorted). A case is written only under its order's lock.</p>
 */
@Service
@Transactional
public class DeliveryFailureService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFailureService.class);

    /** What Sales send to deliver again: why, and what the failed trip is charged at when the customer made it fail. */
    public record Redeliver(String note, BigDecimal failedTripFee) {
    }

    /** What Sales send to cancel what was not delivered: why, and the fee agreed for the goods already made. */
    public record Cancel(String note, CancellationFee agreedFee) {
    }

    /**
     * A case as Sales and the warehouse read it.
     *
     * @param heldUnits        the units of each line held for it again at the warehouse - what a redelivery is released
     *                         with once it equals the case's units
     * @param readyForRelease  decided to deliver again, every unit held, not released yet
     */
    public record CaseView(DeliveryFailureCase failure, String orderNumber, UUID customerId, OrderStatus orderStatus,
                           Map<UUID, Integer> heldUnits, boolean readyForRelease) {
    }

    private final OrderRepository orders;
    private final DeliveryFailureCases cases;
    private final OrderServiceImpl orderService;
    private final ShippingWarehouseService shippingWarehouses;
    private final PaymentService payments;
    private final OrderEventPublisher events;
    private final Clock clock;

    DeliveryFailureService(OrderRepository orders, DeliveryFailureCases cases, OrderServiceImpl orderService,
                           ShippingWarehouseService shippingWarehouses, PaymentService payments,
                           OrderEventPublisher events, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.cases = cases;
        this.orderService = orderService;
        this.shippingWarehouses = shippingWarehouses;
        this.payments = payments;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ opening

    /**
     * The goods of a returned shipment were received back on a confirmed return receipt (03 §4.2 step 12): its delivery
     * failure case is OPEN, with the shipment's lines and the cartons that came. Called through
     * {@link ReturnRequestService#goodsReceived} after the receipt committed, in a transaction of its own; a receipt
     * seen again opens nothing more. The cartons are in quarantine, so nothing is held yet: they are held for the order
     * as they are put away ({@link ShippingWarehouseService#holdReturnedGoods}).
     *
     * @throws IllegalStateException when the order knows the shipment but not as RETURNED - the event stays in the
     *         publication registry and is delivered again, so a defect here is seen rather than lost
     */
    public void goodsReceived(ReturnedGoodsReceived event) {
        if (event.shipmentId() == null) {
            throw new IllegalArgumentException("Return receipt %s is against no shipment".formatted(event.receiptNumber()));
        }
        Order order = orders.findByIdForUpdate(new OrderId(event.orderId())).orElseThrow(() ->
                new IllegalStateException("Return receipt %s names no order %s".formatted(event.receiptNumber(),
                        event.orderId())));
        if (cases.findByShipment(event.shipmentId()).isPresent()) {
            log.info("Order {}: return receipt {} opened its delivery failure case already", order.orderNumber(),
                    event.receiptNumber());
            return;
        }
        OrderShipment shipment = order.shipments().stream().filter(s -> s.id().equals(event.shipmentId())).findFirst()
                .orElse(null);
        if (shipment == null) {
            // An order handed over whole, before shipments were recorded on the order (G4-Q3): its outcome is the
            // order's own (recordReturnedToOrigin) and there is no shipment of it for a case to be about.
            log.warn("Order {}: return receipt {} is against shipment {}, which the order does not know; no delivery "
                    + "failure case is opened", order.orderNumber(), event.receiptNumber(), event.shipmentId());
            return;
        }
        if (!shipment.isReturned()) {
            throw new IllegalStateException("Return receipt %s: shipment %s of order %s is %s, not RETURNED".formatted(
                    event.receiptNumber(), event.shipmentId(), order.orderNumber(), shipment.status()));
        }
        DeliveryFailureCase failure = DeliveryFailureCase.open(Identifiers.newId(), event.orderId(), shipment.id(),
                shipment.deliveryBatchId(), event.receiptId(), event.receiptNumber(), event.warehouseId(),
                shipment.failureReason() == null ? DeliveryFailureReason.OTHER : shipment.failureReason(),
                shipment.failureNote(), shipment.quantities(), event.cartons().stream()
                        .map(c -> new DeliveryFailureCase.Carton(c.lpn(), c.sku(), c.lotNumber(), c.quantity(),
                                c.condition())).toList(),
                event.receivedAt().truncatedTo(ChronoUnit.MICROS));
        cases.save(failure);
        log.info("Order {}: delivery failure case opened for shipment {} ({}), {} carton(s) back on receipt {}",
                order.orderNumber(), shipment.id(), failure.failureReason(), failure.cartons().size(),
                event.receiptNumber());
    }

    // ------------------------------------------------------------------ deciding

    /**
     * (a) Sales decide to deliver the goods again (09 §4.3): the case is REDELIVERY, the order leaves its hold for
     * PARTIALLY_FULFILLED once no case of it waits for a decision, and what of the goods is stock to promise is held for
     * it at once - the rest as it is put away. The warehouse manager then releases it; fulfillment picks, checks and
     * hands it over as a new shipment, which closes the case.
     *
     * <p>A trip the customer made fail - they refused the goods, or the address was wrong - is charged to them: the
     * amount Sales enter is owed on a fee item of the contract, in this transaction. Any other reason charges nothing
     * and is marked for a claim against the carrier.</p>
     *
     * @throws BusinessException {@code DELIVERY_FAILURE_CASE_NOT_FOUND}; {@code DELIVERY_FAILURE_INVALID_TRANSITION}
     *         (not OPEN); {@code VALIDATION_FAILED} (the fee missing, not wanted, or not a whole positive amount; no
     *         note); {@code CONFLICT} when the trip is to be charged and the order has no installments
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "delivery-failure-case", resourceId = "#caseId",
            details = "{to: 'REDELIVERY'}")
    public CaseView redeliver(UUID orderId, UUID caseId, Redeliver command, UUID by) {
        Order order = lock(orderId);
        DeliveryFailureCase failure = load(orderId, caseId);
        Instant now = now();
        failure.requireRedeliverable(command.failedTripFee(), command.note());
        UUID feeItem = null;
        if (command.failedTripFee() != null) {
            // Orders, then installments: the order's row is locked already.
            feeItem = payments.chargeFailedDelivery(new ChargeFailedDeliveryCommand(orderId, command.failedTripFee(),
                    "Failed delivery " + failure.receiptNumber(), by, now)).orElse(null);
        }
        failure.redeliver(command.failedTripFee(), feeItem, command.note(), by, now);
        cases.save(failure);
        boolean othersWait = cases.ofOrder(orderId).stream()
                .anyMatch(other -> other.status() == DeliveryFailureCase.Status.OPEN);
        if (order.heldForDeliveryFailure() && !othersWait) {
            orderService.resumeAfterDeliveryFailure(orderId, command.note(), by, now);
        }
        shippingWarehouses.holdReturnedGoods(orderId);
        log.info("Order {}: the goods of receipt {} are delivered again{}", order.orderNumber(),
                failure.receiptNumber(), command.failedTripFee() == null ? "; the failed trip is the carrier's"
                        : "; the failed trip is charged " + command.failedTripFee().toPlainString());
        return view(cases.find(caseId).orElseThrow());
    }

    /**
     * (b) Sales decide to cancel what was not delivered (17 §4.8 step 33, 15 §4.6 step 19): the hold is lifted and the
     * rest of the order is closed in the same transaction, through the closure of the rest with its fee (4B-9) - the
     * units that came back and those never shipped. The fee agreed for the goods already made is kept; what was
     * collected above the goods delivered and the fee becomes refundable, and the accountant records the refund
     * ({@code refund_record}). Every case of the order still undecided or not handed over again is CANCELLED with it:
     * the closure closes all of the order's undelivered units, not one shipment's.
     *
     * <p>The order then shows its shipments: DELIVERED when some arrived, RETURNED when none did - from where the
     * nightly settlement moves it on once its refunds are made ({@link #followRefunds}).</p>
     *
     * @throws BusinessException {@code DELIVERY_FAILURE_CASE_NOT_FOUND}; {@code DELIVERY_FAILURE_INVALID_TRANSITION}
     *         (the case closed, or its redelivery already released to the warehouse - its pick is under way);
     *         {@code ORDER_NOT_ON_HOLD} / {@code ORDER_INVALID_TRANSITION} for an order with nothing left to close;
     *         as {@code OrderService.closeRemainder} for the fee
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "delivery-failure-case", resourceId = "#caseId",
            details = "{to: 'CANCELLED'}")
    public CaseView cancelUndelivered(UUID orderId, UUID caseId, Cancel command, UUID by) {
        Order order = lock(orderId);
        DeliveryFailureCase failure = load(orderId, caseId);
        Instant now = now();
        List<DeliveryFailureCase> undecided = cases.ofOrder(orderId).stream()
                .filter(other -> !other.status().isClosed()).toList();
        if (failure.status().isClosed() || undecided.stream().anyMatch(DeliveryFailureCase::released)) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, failure.status().isClosed()
                    ? "Delivery failure case of receipt %s is %s and is not decided again".formatted(
                            failure.receiptNumber(), failure.status())
                    : ("Order %s has goods released to the warehouse to be delivered again; they are handed over, or "
                            + "their task is cancelled, before the rest is closed").formatted(order.orderNumber()));
        }
        for (DeliveryFailureCase each : undecided) {
            each.cancel(command.note(), by, now);
            cases.save(each);
        }
        if (order.heldForDeliveryFailure()) {
            orderService.closeUndelivered(orderId, command.note(), command.agreedFee(), by, now);
        } else {
            // The hold was lifted by an earlier decision to deliver again: the order is PARTIALLY_FULFILLED, and its
            // rest is closed as any rest is.
            orderService.closeRemainder(orderId, com.stockflow.order.api.CancellationReasonCode.DELIVERY_FAILED,
                    command.note(), command.agreedFee(), by);
        }
        followRefunds(orderId, by);
        log.info("Order {}: what the failed delivery of receipt {} left undelivered is cancelled", order.orderNumber(),
                failure.receiptNumber());
        return view(cases.find(caseId).orElseThrow());
    }

    // ------------------------------------------------------------------ after a cancellation

    /**
     * An order RETURNED by a cancellation of its undelivered goods - nothing of it was delivered - moves on once
     * everything its settlements made refundable is refunded (15 §4.5 step 17), nothing at all included: REFUNDED when
     * no fee was kept on the closure, PARTIALLY_REFUNDED otherwise. From there the nightly settlement closes it after
     * {@code close-after-days}, once it no longer owes (a fee item not paid keeps it open). Called after the
     * cancellation, after each refund recorded ({@link ReturnedGoodsListener}), and by the nightly settlement for the
     * orders both missed.
     *
     * @return whether the order moved
     */
    public boolean followRefunds(UUID orderId, UUID by) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || order.status() != OrderStatus.RETURNED) {
            return false;
        }
        List<DeliveryFailureCase> ofOrder = cases.ofOrder(orderId);
        boolean cancelled = ofOrder.stream().anyMatch(c -> c.status() == DeliveryFailureCase.Status.CANCELLED);
        if (!cancelled || ofOrder.stream().anyMatch(c -> !c.status().isClosed())) {
            // RETURNED through a return request (RMA): ReturnRequestService.followRefunds moves that order.
            return false;
        }
        var progress = payments.refundProgress(orderId);
        if (!progress.settled()) {
            return false;
        }
        // In full when everything the customer paid is refundable - nothing was kept for the goods made; a fee kept,
        // or still owed, makes it partial. Nothing paid and nothing kept is in full too.
        boolean full = progress.refundable().compareTo(payments.installmentStanding(orderId).paid()) >= 0;
        if (!order.refunded(full, by, now(), "DELIVERY_FAILED_SETTLED")) {
            return false;
        }
        orders.save(order);
        events.publishEventsOf(order);
        log.info("Order {}: its failed delivery is settled, now {}", order.orderNumber(), order.status());
        return true;
    }

    // ------------------------------------------------------------------ reading

    /** The order's delivery failure cases, oldest first. */
    @Transactional(readOnly = true)
    public List<CaseView> ofOrder(UUID orderId) {
        orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        return cases.ofOrder(orderId).stream().map(this::view).toList();
    }

    /**
     * The orders RETURNED by a cancellation of their undelivered goods, oldest first, the first {@code skip} left out:
     * what the nightly settlement offers {@link #followRefunds}.
     */
    @Transactional(readOnly = true)
    public List<UUID> settlementCandidates(int skip, int limit) {
        return cases.ordersReturnedByCancellation(skip, limit);
    }

    /** The cases in {@code status} (any when null), oldest first, at most {@code limit}: what waits for Sales. */
    @Transactional(readOnly = true)
    public List<CaseView> list(DeliveryFailureCase.Status status, int limit) {
        return list(status, null, limit);
    }

    /**
     * The same, of the cases whose goods came back to one of {@code warehouses} when it is not null (a warehouse
     * manager's: {@code OrderWarehouseVisibility.limitOf}); empty: none.
     */
    @Transactional(readOnly = true)
    public List<CaseView> list(DeliveryFailureCase.Status status, Set<UUID> warehouses, int limit) {
        return cases.list(status, warehouses, Math.max(1, Math.min(limit, 200))).stream().map(this::view).toList();
    }

    // ------------------------------------------------------------------ helpers

    private CaseView view(DeliveryFailureCase failure) {
        Order order = orders.findById(new OrderId(failure.orderId())).orElseThrow();
        Map<UUID, Integer> held = failure.status().isClosed() ? Map.of() : shippingWarehouses.heldUnitsOf(failure);
        boolean ready = failure.awaitingRelease() && failure.units().entrySet().stream()
                .allMatch(wanted -> held.getOrDefault(wanted.getKey(), 0) >= wanted.getValue());
        return new CaseView(failure, order.orderNumber().value(), order.customerId(), order.status(), held, ready);
    }

    private Order lock(UUID orderId) {
        return orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private DeliveryFailureCase load(UUID orderId, UUID caseId) {
        return cases.find(caseId).filter(failure -> failure.orderId().equals(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_FAILURE_CASE_NOT_FOUND,
                        "No delivery failure case " + caseId + " on order " + orderId));
    }

    /** Microseconds, as the database keeps them. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
