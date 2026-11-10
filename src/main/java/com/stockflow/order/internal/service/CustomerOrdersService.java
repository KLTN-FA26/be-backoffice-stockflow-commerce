package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link CustomerOrders}: the list and the detail a customer sees of their own orders.
 *
 * <p>Read-only and applies no scope of its own: the customer id is the one the controller resolved from
 * the sign-in, and every query and lookup here is narrowed by it. The mapping rules are static so they
 * can be tested without a database.</p>
 */
@Service
@Transactional(readOnly = true)
class CustomerOrdersService implements CustomerOrders {

    /** The days a customer filters by are Saigon days, as in {@code OrderServiceImpl}. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final SortWhitelist SORT = SortWhitelist.of("placedAt", "orderNumber", "totalAmount")
            .withDefault("placedAt", Sort.Direction.DESC);

    /**
     * Statuses from which the goods are released to the warehouse or beyond. Printing is done outside the system before
     * the goods are received (kltn-docs 07/11), so a released order's print lines are made.
     */
    private static final Set<OrderStatus> PAST_PRODUCTION = java.util.EnumSet.of(OrderStatus.READY_TO_FULFILL,
            OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED, OrderStatus.SHIPPED,
            OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED,
            OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.COMPLETED, OrderStatus.CLOSED);

    static final String AWAITING_CREDIT_APPROVAL = "AWAITING_CREDIT_APPROVAL";
    static final String UNDER_REVIEW = "UNDER_REVIEW";

    private final OrderSearchRepository search;
    private final OrderService orders;
    private final CreditChecks creditChecks;
    private final com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests;

    CustomerOrdersService(OrderSearchRepository search, OrderService orders, CreditChecks creditChecks,
                          com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests) {
        this.cancellationRequests = cancellationRequests;
        this.search = search;
        this.orders = orders;
        this.creditChecks = creditChecks;
    }

    @Override
    public PageResponse<Card> myOrders(UUID customerId, Query query) {
        if (query.placedFrom() != null && query.placedTo() != null && query.placedTo().isBefore(query.placedFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "placedTo is before placedFrom");
        }
        List<OrderStatus> shown = visible(query.statuses());
        if (shown.isEmpty()) {
            // Only DRAFT asked for: nothing the customer may see - and an empty filter would read as "every status".
            return Pages.toResponse(org.springframework.data.domain.Page.<Card>empty(
                    Pages.of(query.page(), query.size())));
        }
        var criteria = new OrderSearchRepository.Criteria(query.search(), shown, customerId,
                query.placedFrom() == null ? null : query.placedFrom().atStartOfDay(BUSINESS_ZONE).toInstant(),
                query.placedTo() == null ? null : query.placedTo().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        var page = search.search(criteria, Pages.of(query.page(), query.size(), SORT.parse(query.sort())));
        List<UUID> ids = page.getContent().stream().map(OrderSummary::orderId).toList();
        Map<UUID, Integer> lineCounts = search.lineCounts(ids);
        var requests = cancellationRequests.latestStatuses(ids);
        return Pages.toResponse(page, row -> new Card(row.orderId(), row.orderNumber(), row.status(),
                statusGroup(row.status(), row.lifecycle()), row.total().amount(),
                row.total().currency().getCurrencyCode(), row.placedAt(), lineCounts.getOrDefault(row.orderId(), 0),
                deliveryState(row.status(), row.lifecycle()),
                requests.containsKey(row.orderId()) ? requests.get(row.orderId()).name() : null));
    }

    @Override
    public Detail myOrder(UUID customerId, UUID orderId) {
        OrderSummary summary = orders.findById(orderId)
                .filter(order -> customerId.equals(order.customerId()))
                // 17 §5.2: a DRAFT order - its contract still being drawn up - is not the customer's to see.
                .filter(order -> order.status() != OrderStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        List<Line> lines = summary.lines().stream()
                .map(line -> new Line(line.lineId(), line.sku(), line.quantity(), line.unitPrice().amount(),
                        line.lineTotal().amount(), line.designSnapshotId(), production(line, summary.status()),
                        line.cartons(), line.unitsPerCarton(), line.taxRate(),
                        line.taxAmount() == null ? null : line.taxAmount().amount()))
                .toList();
        Optional<CreditChecks.Check> latestCheck = summary.status() == OrderStatus.ON_HOLD
                ? creditChecks.latest(orderId)
                : Optional.empty();
        return new Detail(summary.orderId(), summary.orderNumber(), summary.status(),
                statusGroup(summary.status(), summary.lifecycle()),
                holdReasonPublic(summary.status(), latestCheck), summary.total().amount(),
                summary.total().currency().getCurrencyCode(), summary.placedAt(), summary.shippingAddress(),
                lines, timeline(summary, orders.history(orderId)),
                deliveryState(summary.status(), summary.lifecycle()), allowedActions(summary),
                cancellationRequests.latestOf(orderId)
                        .map(r -> new CancellationRequestView(r.status().name(), r.reasonCode(), r.note(),
                                r.requestedAt(), r.decidedAt(), r.decisionNote()))
                        .orElse(null),
                shipments(summary), charges(summary));
    }

    /**
     * G4-2a (09 BR-05): the shipments handed over, oldest first, with nothing internal - no actor, note or money. An
     * order whose goods left as a whole, with no shipment recorded on it (before G4-2a, or a parcel packed before
     * parcels recorded their contents), shows one shipment made from the order's own milestones: no number, carrier or
     * parcel count, because the order never learnt them.
     */
    static List<Shipment> shipments(OrderSummary summary) {
        var lifecycle = summary.lifecycle();
        if (lifecycle == null) {
            return List.of();
        }
        if (lifecycle.shipments().isEmpty()) {
            com.stockflow.order.api.OrderShipmentStatus whole = wholeOrderShipmentStatus(summary.status());
            return whole == null || lifecycle.shippedAt() == null ? List.of()
                    : List.of(new Shipment(null, whole, 0, null, lifecycle.shippedAt(),
                    whole == com.stockflow.order.api.OrderShipmentStatus.HANDED_OVER ? null
                            : whole == com.stockflow.order.api.OrderShipmentStatus.DELIVERED ? lifecycle.deliveredAt()
                            : null,
                    lifecycle.deliveryFailureReason()));
        }
        return lifecycle.shipments().stream()
                .sorted(java.util.Comparator.comparing(OrderSummary.Shipment::handedOverAt))
                .map(s -> {
                    var carriage = s.carriage();
                    return new Shipment(carriage == null ? null : carriage.shipmentCode(), s.status(),
                            carriage == null ? 0 : carriage.packageCount(),
                            carriage == null ? null : carriage.carrierName(), s.handedOverAt(), s.outcomeAt(),
                            s.failureReason());
                })
                .toList();
    }

    /**
     * G3-2: what the portal may offer, from the same rules the order applies - so the portal stops keeping its own
     * list of cancellable statuses. A customer cancels at once until the order is released; after that, up to the
     * first completed package, asks (one request at a time).
     */
    private AllowedActions allowedActions(OrderSummary summary) {
        OrderStatus status = summary.status();
        boolean cancel = status.customerMayCancelDirectly();
        var lifecycle = summary.lifecycle();
        boolean packed = lifecycle != null && lifecycle.packedAt() != null;
        boolean request = !cancel && !packed && status.canTransitionTo(OrderStatus.CANCELLED)
                && !cancellationRequests.hasPending(summary.orderId());
        return new AllowedActions(cancel, request);
    }

    static StatusGroup statusGroup(OrderStatus status) {
        return statusGroup(status, null);
    }

    /**
     * The statuses a customer's list may show (17 §5.2): those asked for, or every one, but never DRAFT - an order whose
     * contract is still being drawn up is Sales' work, not yet the customer's.
     */
    private static List<OrderStatus> visible(List<OrderStatus> asked) {
        List<OrderStatus> from = asked == null || asked.isEmpty() ? List.of(OrderStatus.values()) : asked;
        return from.stream().filter(status -> status != OrderStatus.DRAFT).distinct().toList();
    }

    /** {@code lifecycle} tells a CLOSED order that was refunded from one that was completed; null reads as completed. */
    static StatusGroup statusGroup(OrderStatus status, OrderSummary.Lifecycle lifecycle) {
        return switch (status) {
            // A DRAFT order is not the customer's to see (17 §5.2): the reads leave it out; were one read, it waits.
            case DRAFT, PENDING_PAYMENT -> StatusGroup.AWAITING_PAYMENT;
            case PENDING_CONTRACT -> StatusGroup.AWAITING_CONTRACT;
            case ON_HOLD -> StatusGroup.UNDER_REVIEW;
            case CONFIRMED -> StatusGroup.CONFIRMED;
            // 17 §5.2: "Đang chờ hàng".
            case AWAITING_STOCK -> StatusGroup.AWAITING_STOCK;
            case IN_PRODUCTION -> StatusGroup.IN_PRODUCTION;
            case READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED, PACKED -> StatusGroup.PREPARING;
            case SHIPPED, DELIVERY_FAILED -> StatusGroup.SHIPPING;
            case DELIVERED, COMPLETED -> StatusGroup.DELIVERED;
            // Closed after a refund (never completed) is a return; closed after completion a delivery.
            case CLOSED -> lifecycle != null && lifecycle.completedAt() == null ? StatusGroup.RETURNED
                    : StatusGroup.DELIVERED;
            case CANCELLED -> StatusGroup.CANCELLED;
            case RETURN_REQUESTED, RETURNED, REFUNDED, PARTIALLY_REFUNDED -> StatusGroup.RETURNED;
        };
    }

    /** Where an order that left as a whole stands, read as one shipment; null before it left or once cancelled. */
    private static com.stockflow.order.api.OrderShipmentStatus wholeOrderShipmentStatus(OrderStatus status) {
        return switch (status) {
            case SHIPPED -> com.stockflow.order.api.OrderShipmentStatus.HANDED_OVER;
            case DELIVERY_FAILED -> com.stockflow.order.api.OrderShipmentStatus.DELIVERY_FAILED;
            case DELIVERED, RETURN_REQUESTED, COMPLETED, CLOSED -> com.stockflow.order.api.OrderShipmentStatus.DELIVERED;
            case RETURNED, REFUNDED, PARTIALLY_REFUNDED -> com.stockflow.order.api.OrderShipmentStatus.RETURNED;
            default -> null;
        };
    }

    static DeliveryState deliveryState(OrderStatus status) {
        return deliveryState(status, null);
    }

    /**
     * Only what staff recorded: a shipped order is in transit until someone records its delivery. With shipments
     * (split, G4-Q3) it sums them up (09 BR-05): partly delivered once one arrived and another has not.
     * {@code lifecycle} may be null (a list row without its shipments): the status alone decides then.
     */
    static DeliveryState deliveryState(OrderStatus status, OrderSummary.Lifecycle lifecycle) {
        List<OrderSummary.Shipment> shipments = lifecycle == null ? List.of() : lifecycle.shipments();
        boolean anyDelivered = shipments.stream()
                .anyMatch(s -> s.status() == com.stockflow.order.api.OrderShipmentStatus.DELIVERED);
        boolean anyInTransit = shipments.stream()
                .anyMatch(s -> s.status() == com.stockflow.order.api.OrderShipmentStatus.HANDED_OVER);
        boolean anyFailed = shipments.stream()
                .anyMatch(s -> s.status() == com.stockflow.order.api.OrderShipmentStatus.DELIVERY_FAILED);
        return switch (status) {
            case SHIPPED -> anyDelivered ? DeliveryState.PARTIALLY_DELIVERED : DeliveryState.IN_TRANSIT;
            case DELIVERY_FAILED -> anyDelivered ? DeliveryState.PARTIALLY_DELIVERED : DeliveryState.DELIVERY_FAILED;
            case PARTIALLY_FULFILLED, PACKED, ON_HOLD -> anyDelivered ? DeliveryState.PARTIALLY_DELIVERED
                    : anyInTransit || status == OrderStatus.PARTIALLY_FULFILLED && shipments.isEmpty()
                    ? DeliveryState.IN_TRANSIT
                    : anyFailed ? DeliveryState.DELIVERY_FAILED : DeliveryState.NOT_SHIPPED;
            case DELIVERED, COMPLETED, RETURN_REQUESTED -> DeliveryState.DELIVERED;
            case CLOSED -> statusGroup(status, lifecycle) == StatusGroup.RETURNED ? DeliveryState.RETURNED
                    : DeliveryState.DELIVERED;
            case RETURNED, REFUNDED, PARTIALLY_REFUNDED -> DeliveryState.RETURNED;
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL,
                 PICKING, CANCELLED -> DeliveryState.NOT_SHIPPED;
        };
    }

    /**
     * Where a print line's goods stand, from the order's status alone: printing is done outside the system, so the
     * goods of a released order are made - all of the line's units - and nothing is made before release.
     */
    static Production production(OrderSummary.LineSummary line, OrderStatus orderStatus) {
        if (line.designSnapshotId() == null) {
            return new Production(ProductionState.NOT_REQUIRED, 0);
        }
        if (orderStatus == OrderStatus.CANCELLED) {
            return new Production(ProductionState.CANCELLED, 0);
        }
        if (PAST_PRODUCTION.contains(orderStatus)) {
            return new Production(ProductionState.DONE, line.quantity());
        }
        return new Production(ProductionState.NOT_STARTED, 0);
    }

    /**
     * A customer is told whether the shop is waiting on a credit decision or just looking at the order;
     * the hold's notes and internal reason are never passed on.
     */
    static String holdReasonPublic(OrderStatus status, Optional<CreditChecks.Check> latestCheck) {
        if (status != OrderStatus.ON_HOLD) {
            return null;
        }
        boolean awaitingCredit = latestCheck
                .filter(check -> check.outcome() == CreditChecks.Outcome.OVER_LIMIT
                        || check.outcome() == CreditChecks.Outcome.OVERDUE)
                .filter(check -> check.decidedAt() == null)
                .isPresent();
        return awaitingCredit ? AWAITING_CREDIT_APPROVAL : UNDER_REVIEW;
    }

    /**
     * Oldest first, from the status history. The history's reason column holds what staff or the
     * customer typed, so only the cancellation's reason <i>code</i> is taken, from the order itself.
     * An order with no history reads as one entry, the status it is in.
     */
    static List<TimelineEntry> timeline(OrderSummary summary, List<OrderStatusChange> history) {
        List<TimelineEntry> entries = new ArrayList<>();
        for (OrderStatusChange change : history) {
            // A step into DRAFT - the order made from the quote, or its contract withdrawn - is Sales' drafting, not a
            // step the customer reads (17 §5.2).
            if (change.to() == null || change.to() == OrderStatus.DRAFT) {
                continue;
            }
            var reasonCode = change.to() == OrderStatus.CANCELLED && summary.cancellation() != null
                    ? summary.cancellation().reasonCode()
                    : null;
            entries.add(new TimelineEntry(change.to(), statusGroup(change.to()), change.occurredAt(), reasonCode));
        }
        if (entries.isEmpty()) {
            entries.add(new TimelineEntry(summary.status(), statusGroup(summary.status()), summary.placedAt(), null));
        }
        return List.copyOf(entries);
    }

    /** Before tax, the VAT and the fee lines; an order placed without VAT is all before tax, with no fee lines. */
    private static CustomerOrders.Charges charges(OrderSummary summary) {
        if (summary.charges() == null) {
            return new CustomerOrders.Charges(summary.total().amount(), java.math.BigDecimal.ZERO, List.of());
        }
        OrderSummary.Charges charges = summary.charges();
        return new CustomerOrders.Charges(charges.netTotal().amount(), charges.taxTotal().amount(),
                charges.fees() == null ? List.of() : charges.fees().stream()
                        .map(fee -> new CustomerOrders.Fee(fee.kind().name(), fee.sku(), fee.amount().amount(),
                                fee.taxRate(), fee.taxAmount().amount()))
                        .toList());
    }
}
