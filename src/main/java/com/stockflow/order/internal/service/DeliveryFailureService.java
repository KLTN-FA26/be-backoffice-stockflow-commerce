package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.RedeliveryWithdrawn;
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
import org.springframework.context.ApplicationEventPublisher;
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
 *   no receipt yet (goods lost, or not received) ─▶ cancel without a case                 ({@link #cancelWithoutCase})
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

    /** The decision note of a case whose goods came back after the rest of the order was closed. */
    static final String CLOSED_BEFORE_RECEIVED = "Closed with the rest of the order before its goods were received back";

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
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    DeliveryFailureService(OrderRepository orders, DeliveryFailureCases cases, OrderServiceImpl orderService,
                           ShippingWarehouseService shippingWarehouses, PaymentService payments,
                           OrderEventPublisher events, ApplicationEventPublisher publisher, Clock clock) {
        this.events = events;
        this.publisher = publisher;
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
     * <p>Goods received back after the rest of the order was closed - Sales cancelled what was not delivered while this
     * shipment's receipt was still to be made, or before it came back at all - are owed to nobody: the case opens and is
     * CANCELLED at once, by the receiving clerk, with a note saying why (WF-05 review F1). It is on record and holds
     * nothing; an OPEN case there would wait for a decision no path can take.</p>
     *
     * @throws IllegalStateException when the order knows the shipment but not as RETURNED, or when the units that came
     *         back, still owed, are not whole cartons of their lines (WF-05 review N3: such a case could be held but
     *         never released) - the publication stays incomplete in the registry and is replayed at the next start, so
     *         a defect here is seen rather than lost; the order stays held, and Sales may cancel without a case
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
            // order's own (recordReturnedToOrigin) and there is no shipment of it for a case to be about. Historical
            // data only (WF-05 review F13): a shipment leaves whole only when its parcels record no contents, which
            // nothing packed since #136 does, so no current flow comes here. Such an order goes RETURNED with no
            // case, as it did before WF-05.
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
        if (!order.owesUnitsOf(shipment.id())) {
            // ck_delivery_failure_case_* and the trigger allow a case to be born OPEN only: it is opened, then closed.
            // It holds nothing, so whether its units are whole cartons does not matter.
            failure = cases.save(failure);
            failure.cancel(CLOSED_BEFORE_RECEIVED, event.receivedBy(), failure.openedAt());
            cases.save(failure);
            log.info("Order {}: the goods of shipment {} came back on receipt {} after the rest was closed; its case is "
                    + "cancelled", order.orderNumber(), shipment.id(), event.receiptNumber());
            return;
        }
        Map<UUID, Integer> unitsPerCarton = new java.util.HashMap<>();
        order.lines().forEach(line -> unitsPerCarton.put(line.id(),
                line.unitsPerCarton() == null || line.unitsPerCarton() < 1 ? 1 : line.unitsPerCarton()));
        failure.requireWholeCartons(unitsPerCarton);
        cases.save(failure);
        log.info("Order {}: delivery failure case opened for shipment {} ({}), {} carton(s) back on receipt {}",
                order.orderNumber(), shipment.id(), failure.failureReason(), failure.cartons().size(),
                event.receiptNumber());
    }

    // ------------------------------------------------------------------ deciding

    /**
     * (a) Sales decide to deliver the goods again (09 §4.3): the case is REDELIVERY, the order leaves its hold for
     * PARTIALLY_FULFILLED once nothing of it waits for a decision ({@link #decisionsWait}: a case OPEN, or a shipment
     * that came back and whose receipt is still to be made), and what of the goods is stock to promise is held for
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
        if (order.heldForDeliveryFailure() && !decisionsWait(order, cases.ofOrder(orderId))) {
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
     * the closure closes all of the order's undelivered units, not one shipment's - a shipment that came back and whose
     * receipt is still to be made included, whose case is then born CANCELLED ({@link #goodsReceived}).
     *
     * <p>A redelivery already released to the warehouse is withdrawn with it while nothing of it is picked (WF-05
     * review F3): the closure releases its allocations and {@code RedeliveryWithdrawn} has fulfillment cancel its task.
     * Once a pick took a carton the goods are on their way to the customer and are handed over; what they do not keep
     * comes back through a return request.</p>
     *
     * <p>The order then shows its shipments: DELIVERED when some arrived, RETURNED when none did - from where the
     * nightly settlement moves it on once its refunds are made ({@link #followRefunds}).</p>
     *
     * @throws BusinessException {@code DELIVERY_FAILURE_CASE_NOT_FOUND}; {@code DELIVERY_FAILURE_INVALID_TRANSITION}
     *         (the case closed, or goods of a released redelivery picked already);
     *         {@code DELIVERY_FAILURE_REST_NOT_CLOSABLE} (units still owed, the order on hold for another reason or
     *         packed); {@code VALIDATION_FAILED} for a fee when nothing is left to close; as
     *         {@code OrderService.closeRemainder} for the fee
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "delivery-failure-case", resourceId = "#caseId",
            details = "{to: 'CANCELLED'}")
    public CaseView cancelUndelivered(UUID orderId, UUID caseId, Cancel command, UUID by) {
        Order order = lock(orderId);
        DeliveryFailureCase failure = load(orderId, caseId);
        if (failure.status().isClosed()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION,
                    "Delivery failure case of receipt %s is %s and is not decided again".formatted(
                            failure.receiptNumber(), failure.status()));
        }
        closeUndelivered(order, command, by);
        log.info("Order {}: what the failed delivery of receipt {} left undelivered is cancelled", order.orderNumber(),
                failure.receiptNumber());
        return view(cases.find(caseId).orElseThrow());
    }

    /**
     * (c) Sales cancel what was not delivered while the order is held for a shipment that came back and no case of it
     * exists yet (WF-05 review F6.1): the goods were lost on the way back, or the warehouse has not made the return
     * receipt and the customer will not wait. The same closure as {@link #cancelUndelivered}, the same money rules, the
     * note mandatory - it is the only record of why the order closed before its goods were seen. Goods received back
     * afterwards open a case that is CANCELLED at once ({@link #goodsReceived}).
     *
     * @return the order's cases afterwards, oldest first
     * @throws BusinessException {@code NOT_FOUND}; {@code DELIVERY_FAILURE_INVALID_TRANSITION} when the order is not
     *         held for a failed delivery, or every shipment that came back has its case (decide that case);
     *         {@code VALIDATION_FAILED} without a note; as {@link #cancelUndelivered} otherwise
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "order", resourceId = "#orderId",
            details = "{closed: 'UNDELIVERED_WITHOUT_CASE'}")
    public List<CaseView> cancelWithoutCase(UUID orderId, Cancel command, UUID by) {
        if (command.note() == null || command.note().isBlank() || command.note().strip().length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Cancelling without the goods received back says why, in at most 1000 characters");
        }
        Order order = lock(orderId);
        if (!order.heldForDeliveryFailure()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION,
                    "Order %s is %s, not held for a failed delivery".formatted(order.orderNumber(), order.status()));
        }
        if (uncased(order, cases.ofOrder(orderId)).isEmpty()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Every shipment of order %s that "
                    + "came back has its delivery failure case; decide the case").formatted(order.orderNumber()));
        }
        closeUndelivered(order, command, by);
        log.info("Order {}: what was not delivered is cancelled before the goods that came back were received",
                order.orderNumber());
        return cases.ofOrder(orderId).stream().map(this::view).toList();
    }

    /**
     * The closure both decisions share, on the locked order: every case not closed is CANCELLED, the rest of the order
     * is closed with the agreed fee, a released redelivery is withdrawn from the warehouse, and a RETURNED order is
     * offered to {@link #followRefunds}.
     *
     * <p>An order no longer held whose rest was closed already - nothing outstanding - only has its cases closed: there
     * is nothing for a fee to be kept on, so one is refused rather than dropped.</p>
     *
     * <p>An order that still owes units but is neither held for the failed delivery nor partly fulfilled - put on hold
     * for another reason after a decision to deliver again, or packed - is refused (WF-05 review N1). Cancelling only
     * its cases there would leave the units owed with no case to deliver them, the case's holds and allocations on the
     * order lines, and money unsettled; and cancelling across the other hold would decide that hold for whoever put it
     * on. The rest of an order is closed from PARTIALLY_FULFILLED only ({@code Order.closeRemainder}): the hold is
     * resolved first, as for any closure, and the cancellation is made then.</p>
     *
     * <p>It closes every undelivered unit of the order, later batches included, not only the failed shipment's (WF-05
     * review F6.2, kept as is): units are closed per line, not per batch; the release and handover of the next batch
     * ({@code nextToRelease}, {@code deliveredBatches}, {@code check_delivery_batch_of_order}) require the batch before
     * it handed over in full; the payment closure takes the latest batch first; and {@code OrderRemainderClosed} names
     * no batch. Closing one batch is an open question in the WF05-dfail report.</p>
     */
    private void closeUndelivered(Order order, Cancel command, UUID by) {
        UUID orderId = order.id().value();
        Instant now = now();
        List<DeliveryFailureCase> undecided = cases.ofOrder(orderId).stream()
                .filter(other -> !other.status().isClosed()).toList();
        List<DeliveryFailureCase> withdrawn = undecided.stream().filter(DeliveryFailureCase::released).toList();
        for (DeliveryFailureCase released : withdrawn) {
            if (shippingWarehouses.pickedFor(released)) {
                throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Order %s: the goods of "
                        + "receipt %s are picked to be delivered again; they are handed over, and what the customer "
                        + "does not keep comes back on a return request").formatted(order.orderNumber(),
                        released.receiptNumber()));
            }
        }
        boolean held = order.heldForDeliveryFailure();
        boolean restToClose = held
                || (order.status() == OrderStatus.PARTIALLY_FULFILLED && order.outstandingUnits() > 0);
        if (!restToClose && order.outstandingUnits() > 0) {
            // WF-05 review N1: owed units with no rest to close here - closing only the cases would strand them.
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_REST_NOT_CLOSABLE, ("Order %s is %s%s and still "
                    + "owes %d unit(s); %s before cancelling what was not delivered").formatted(order.orderNumber(),
                    order.status(), order.holdReason() == null ? "" : " (" + order.holdReason() + ")",
                    order.outstandingUnits(), order.status() == OrderStatus.ON_HOLD ? "resolve its hold"
                            : "let what is packed leave"));
        }
        if (!restToClose && command.agreedFee() != null && command.agreedFee().amount().signum() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Order %s has nothing left to close; no fee is "
                    + "kept on it").formatted(order.orderNumber()));
        }
        for (DeliveryFailureCase each : undecided) {
            each.cancel(command.note(), by, now);
            cases.save(each);
        }
        if (held) {
            orderService.closeUndelivered(orderId, command.note(), command.agreedFee(), by, now);
        } else if (restToClose) {
            // The hold was lifted by an earlier decision to deliver again: the order is PARTIALLY_FULFILLED, and its
            // rest is closed as any rest is.
            orderService.closeRemainder(orderId, com.stockflow.order.api.CancellationReasonCode.DELIVERY_FAILED,
                    command.note(), command.agreedFee(), by);
        } else {
            // Nothing outstanding (checked above): its rest was closed before - cases left open by WF-05 review F1
            // before its fix - so only the cases close.
            log.info("Order {} is {} with nothing outstanding; only its delivery failure cases are cancelled",
                    order.orderNumber(), order.status());
        }
        // After the closure released the allocations: fulfillment stops the task picking them.
        withdrawn.forEach(released -> publisher.publishEvent(new RedeliveryWithdrawn(orderId,
                order.orderNumber().value(), released.id(), by, now)));
        followRefunds(orderId, by);
    }

    // ------------------------------------------------------------------ after a cancellation

    /**
     * An order RETURNED by a cancellation of its undelivered goods - nothing of it was delivered - moves on once
     * everything its settlements made refundable is refunded (15 §4.5 step 17), nothing at all included: REFUNDED when
     * money went back and it was everything the customer paid, nothing kept and nothing still owed; PARTIALLY_REFUNDED
     * otherwise - a fee kept or owed, or nothing paid and so nothing refunded (WF-05 review F8). From there the nightly
     * settlement closes it after
     * {@code close-after-days}, once it no longer owes (a fee item not paid keeps it open). Called after the
     * cancellation, after each refund recorded ({@link ReturnedGoodsListener}), and by the nightly settlement for the
     * orders both missed.
     *
     * <p>An order with a shipment DELIVERED is not one of them (WF-05 review F2): it went RETURNED through a return
     * request after a cancelled delivery failure left it DELIVERED, and the return request moves it
     * ({@code ReturnRequestService.followRefunds}) once it is decided - not this, while it may still be open.</p>
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
        boolean delivered = order.shipments().stream()
                .anyMatch(s -> s.status() == com.stockflow.order.api.OrderShipmentStatus.DELIVERED);
        if (!cancelled || delivered || ofOrder.stream().anyMatch(c -> !c.status().isClosed())) {
            // RETURNED through a return request (RMA): ReturnRequestService.followRefunds moves that order.
            return false;
        }
        var progress = payments.refundProgress(orderId);
        if (!progress.settled()) {
            return false;
        }
        // In full when money went back and it was everything the customer paid, with nothing still owed on the
        // installments: a fee kept (refundable below paid) or owed (owed above paid - a closure's fee item, a failed
        // trip's) makes it partial, and so does nothing paid - nothing was refunded.
        var standing = payments.installmentStanding(orderId);
        boolean full = progress.refundable().signum() > 0
                && progress.refundable().compareTo(standing.paid()) >= 0
                && standing.total().compareTo(standing.paid()) <= 0;
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

    /**
     * What still waits for Sales on the order (WF-05 review F1): a case OPEN, or a shipment that came back, whose units
     * the order still owes, with no case yet - its return receipt is still to be made. While anything waits the
     * DELIVERY_FAILED hold stays: lifting it would let the order's next batch go before that decision.
     */
    private static boolean decisionsWait(Order order, List<DeliveryFailureCase> ofOrder) {
        return ofOrder.stream().anyMatch(c -> c.status() == DeliveryFailureCase.Status.OPEN)
                || !uncased(order, ofOrder).isEmpty();
    }

    /** The order's shipments that came back, still owed, with no delivery failure case yet. */
    private static List<OrderShipment> uncased(Order order, List<DeliveryFailureCase> ofOrder) {
        Set<UUID> cased = new java.util.HashSet<>();
        ofOrder.forEach(c -> cased.add(c.shipmentId()));
        return order.shipments().stream()
                .filter(s -> s.isReturned() && !cased.contains(s.id()) && order.owesUnitsOf(s.id())).toList();
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
