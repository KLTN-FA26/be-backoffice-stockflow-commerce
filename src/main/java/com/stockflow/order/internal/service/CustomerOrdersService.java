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
import com.stockflow.order.internal.domain.ProductionRecords;
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

    /** Statuses from which the goods are released to the warehouse or beyond: printing, if any, is over. */
    private static final Set<OrderStatus> PAST_PRODUCTION = java.util.EnumSet.of(OrderStatus.READY_TO_FULFILL,
            OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.PACKED, OrderStatus.SHIPPED,
            OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED,
            OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.COMPLETED, OrderStatus.CLOSED);

    static final String AWAITING_CREDIT_APPROVAL = "AWAITING_CREDIT_APPROVAL";
    static final String UNDER_REVIEW = "UNDER_REVIEW";

    private final OrderSearchRepository search;
    private final OrderService orders;
    private final ProductionRecords productionRecords;
    private final CreditChecks creditChecks;
    private final com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests;
    private final com.stockflow.order.internal.domain.QuoteRepository quotes;

    CustomerOrdersService(OrderSearchRepository search, OrderService orders, ProductionRecords productionRecords,
                          CreditChecks creditChecks,
                          com.stockflow.order.internal.domain.CancellationRequestRepository cancellationRequests,
                          com.stockflow.order.internal.domain.QuoteRepository quotes) {
        this.cancellationRequests = cancellationRequests;
        this.quotes = quotes;
        this.search = search;
        this.orders = orders;
        this.productionRecords = productionRecords;
        this.creditChecks = creditChecks;
    }

    @Override
    public PageResponse<Card> myOrders(UUID customerId, Query query) {
        if (query.placedFrom() != null && query.placedTo() != null && query.placedTo().isBefore(query.placedFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "placedTo is before placedFrom");
        }
        var criteria = new OrderSearchRepository.Criteria(query.search(), query.statuses(), customerId,
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
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        Map<UUID, Integer> produced = summary.lines().stream().anyMatch(line -> line.designSnapshotId() != null)
                ? productionRecords.producedByLine(orderId)
                : Map.of();
        List<Line> lines = summary.lines().stream()
                .map(line -> new Line(line.lineId(), line.sku(), line.quantity(), line.unitPrice().amount(),
                        line.lineTotal().amount(), line.designSnapshotId(),
                        production(line, summary.status(), produced)))
                .toList();
        Optional<CreditChecks.Check> latestCheck = summary.status() == OrderStatus.ON_HOLD
                ? creditChecks.latest(orderId)
                : Optional.empty();
        return new Detail(summary.orderId(), summary.orderNumber(), summary.status(),
                statusGroup(summary.status(), summary.lifecycle()),
                holdReasonPublic(summary.status(), latestCheck), summary.paymentTerm(), summary.total().amount(),
                summary.total().currency().getCurrencyCode(), summary.placedAt(), summary.shippingAddress(),
                payment(summary), lines, timeline(summary, orders.history(orderId)),
                deliveryState(summary.status(), summary.lifecycle()), allowedActions(summary),
                cancellationRequests.latestOf(orderId)
                        .map(r -> new CancellationRequestView(r.status().name(), r.reasonCode(), r.note(),
                                r.requestedAt(), r.decidedAt(), r.decisionNote()))
                        .orElse(null));
    }

    /**
     * G3-2: what the portal may offer, from the same rules the order applies - so the portal stops keeping its own
     * list of cancellable statuses. A customer cancels at once until the order is released; after that, up to the
     * first completed package, asks (one request at a time); reorders an order that came from a quote.
     */
    private AllowedActions allowedActions(OrderSummary summary) {
        OrderStatus status = summary.status();
        boolean cancel = status.customerMayCancelDirectly();
        boolean packed = summary.lifecycle() != null && summary.lifecycle().packedAt() != null;
        boolean request = !cancel && !packed && status.canTransitionTo(OrderStatus.CANCELLED)
                && !cancellationRequests.hasPending(summary.orderId());
        return new AllowedActions(cancel, request, quotes.findByConvertedOrderId(summary.orderId()).isPresent());
    }

    static StatusGroup statusGroup(OrderStatus status) {
        return statusGroup(status, null);
    }

    /** {@code lifecycle} tells a CLOSED order that was refunded from one that was completed; null reads as completed. */
    static StatusGroup statusGroup(OrderStatus status, OrderSummary.Lifecycle lifecycle) {
        return switch (status) {
            case DRAFT, PENDING_PAYMENT -> StatusGroup.AWAITING_PAYMENT;
            case ON_HOLD -> StatusGroup.UNDER_REVIEW;
            case CONFIRMED -> StatusGroup.CONFIRMED;
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
            case DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, CANCELLED ->
                    DeliveryState.NOT_SHIPPED;
        };
    }

    /**
     * @param producedByLine good units per line, from {@code ProductionRecords.producedByLine}, read once
     *                       for the order
     */
    static Production production(OrderSummary.LineSummary line, OrderStatus orderStatus,
                                 Map<UUID, Integer> producedByLine) {
        if (line.designSnapshotId() == null) {
            return new Production(ProductionState.NOT_REQUIRED, 0);
        }
        int produced = producedByLine.getOrDefault(line.lineId(), 0);
        if (orderStatus == OrderStatus.CANCELLED) {
            return new Production(ProductionState.CANCELLED, produced);
        }
        if (produced >= line.quantity() || PAST_PRODUCTION.contains(orderStatus)) {
            return new Production(ProductionState.DONE, produced);
        }
        if (orderStatus == OrderStatus.IN_PRODUCTION) {
            return new Production(ProductionState.IN_PROGRESS, produced);
        }
        return new Production(ProductionState.NOT_STARTED, produced);
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
            if (change.to() == null) {
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

    private static Payment payment(OrderSummary summary) {
        var payment = summary.payment();
        return payment == null ? null
                : new Payment(payment.status(), payment.paidAmount(), payment.depositRequired(),
                        payment.creditTermDays());
    }
}
