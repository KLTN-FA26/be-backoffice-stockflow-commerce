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
    private static final Set<OrderStatus> PAST_PRODUCTION = Set.of(OrderStatus.READY_TO_FULFILL,
            OrderStatus.IN_FULFILMENT, OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED,
            OrderStatus.RETURNED);

    static final String AWAITING_CREDIT_APPROVAL = "AWAITING_CREDIT_APPROVAL";
    static final String UNDER_REVIEW = "UNDER_REVIEW";

    private final OrderSearchRepository search;
    private final OrderService orders;
    private final ProductionRecords productionRecords;
    private final CreditChecks creditChecks;

    CustomerOrdersService(OrderSearchRepository search, OrderService orders, ProductionRecords productionRecords,
                          CreditChecks creditChecks) {
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
        Map<UUID, Integer> lineCounts = search.lineCounts(page.getContent().stream().map(OrderSummary::orderId).toList());
        return Pages.toResponse(page, row -> new Card(row.orderId(), row.orderNumber(), row.status(),
                statusGroup(row.status()), row.total().amount(), row.total().currency().getCurrencyCode(),
                row.placedAt(), lineCounts.getOrDefault(row.orderId(), 0), deliveryState(row.status())));
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
        return new Detail(summary.orderId(), summary.orderNumber(), summary.status(), statusGroup(summary.status()),
                holdReasonPublic(summary.status(), latestCheck), summary.paymentTerm(), summary.total().amount(),
                summary.total().currency().getCurrencyCode(), summary.placedAt(), summary.shippingAddress(),
                payment(summary), lines, timeline(summary, orders.history(orderId)),
                deliveryState(summary.status()));
    }

    static StatusGroup statusGroup(OrderStatus status) {
        return switch (status) {
            case DRAFT, PENDING_PAYMENT -> StatusGroup.AWAITING_PAYMENT;
            case ON_HOLD -> StatusGroup.UNDER_REVIEW;
            case CONFIRMED -> StatusGroup.CONFIRMED;
            case IN_PRODUCTION -> StatusGroup.IN_PRODUCTION;
            case READY_TO_FULFILL, IN_FULFILMENT -> StatusGroup.PREPARING;
            case SHIPPED -> StatusGroup.SHIPPING;
            case DELIVERED, COMPLETED -> StatusGroup.DELIVERED;
            case CANCELLED -> StatusGroup.CANCELLED;
            case RETURNED -> StatusGroup.RETURNED;
        };
    }

    /** Only what staff recorded: a shipped order is in transit until someone records its delivery. */
    static DeliveryState deliveryState(OrderStatus status) {
        return switch (status) {
            case SHIPPED -> DeliveryState.IN_TRANSIT;
            case DELIVERED, COMPLETED -> DeliveryState.DELIVERED;
            case RETURNED -> DeliveryState.RETURNED;
            case DRAFT, PENDING_PAYMENT, ON_HOLD, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, IN_FULFILMENT,
                 CANCELLED -> DeliveryState.NOT_SHIPPED;
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
