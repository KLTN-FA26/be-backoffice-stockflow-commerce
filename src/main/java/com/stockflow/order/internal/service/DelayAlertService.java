package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.DelayAlertOpened;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.DelayAlert;
import com.stockflow.order.internal.domain.DelayAlertEvaluator;
import com.stockflow.order.internal.domain.DelayAlertRepository;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.ProductionScheduleQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The delay alerts of the production schedule (4A-7, kltn-docs 17 §4.12 steps 48-49, BR-30): recomputed for one sales
 * order after an operation that may change them ({@code DelayAlertListener}) and for every followed order each night
 * ({@code DelayAlertSweepJob}). The rules are {@link DelayAlertEvaluator}'s; this reads the facts and writes what
 * changed. An alert opening publishes {@link DelayAlertOpened}, which the notification module turns into in-app
 * messages; a close tells nobody.
 *
 * <p><b>Never blocking.</b> A recompute runs in a transaction of its own after the operation committed, so its failure
 * reaches no operation: the listener's event stays incomplete in the registry and is retried, and the nightly sweep is
 * the last net. Nothing reads an alert to refuse anything.</p>
 *
 * <p><b>One at a time per order.</b> A recompute takes the order's advisory lock ({@link DelayAlertRepository#lockSalesOrder})
 * before reading anything, so two of them for one order - a listener and the sweep - run one after the other and the
 * later one sees what the earlier committed; the unique index of open alerts would make a duplicate harmless anyway.</p>
 *
 * <p><b>Which orders are followed.</b> PO_NEEDED while the order's shortage turns into production orders
 * ({@link #SHORTAGE_FOLLOWED}: confirmed - the shortage is counted from what it holds, which release turns into
 * allocations that stop counting once picked); DELIVERY_LATE while it still has goods to hand over
 * ({@link #DELIVERY_FOLLOWED}). 4A-6 adds {@code AWAITING_STOCK} to both: one constant each.</p>
 */
@Service
public class DelayAlertService {

    private static final Logger log = LoggerFactory.getLogger(DelayAlertService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 17 §4.2 step 5: the order whose shortage becomes production orders. */
    static final Set<OrderStatus> SHORTAGE_FOLLOWED = Collections.unmodifiableSet(EnumSet.of(OrderStatus.CONFIRMED));

    /**
     * Confirmed and not yet all handed over (17 §4.5): an order on hold still owes its batches. Before confirmation the
     * seller is not yet bound to deliver; from SHIPPED on everything has left.
     */
    static final Set<OrderStatus> DELIVERY_FOLLOWED = Collections.unmodifiableSet(EnumSet.of(OrderStatus.CONFIRMED,
            OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED,
            OrderStatus.ON_HOLD));

    /** What one recompute changed. */
    public record Recomputed(int opened, int closed) {
    }

    private final DelayAlertRepository alerts;
    private final ProductionScheduleQuery query;
    private final OrderRepository orders;
    private final ProductionOrderRepository productionOrders;
    private final ProductionOrderService productionOrderService;
    private final SalesContractRepository contracts;
    private final ProducerRepository producers;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int defaultLeadDays;

    DelayAlertService(DelayAlertRepository alerts, ProductionScheduleQuery query, OrderRepository orders,
                      ProductionOrderRepository productionOrders, ProductionOrderService productionOrderService,
                      SalesContractRepository contracts, ProducerRepository producers,
                      ApplicationEventPublisher events, Clock clock,
                      @Value("${stockflow.production-schedule.default-lead-days:14}") int defaultLeadDays) {
        if (defaultLeadDays < 0) {
            throw new IllegalArgumentException("stockflow.production-schedule.default-lead-days must not be negative");
        }
        this.alerts = alerts;
        this.query = query;
        this.orders = orders;
        this.productionOrders = productionOrders;
        this.productionOrderService = productionOrderService;
        this.contracts = contracts;
        this.producers = producers;
        this.events = events;
        this.clock = clock;
        this.defaultLeadDays = defaultLeadDays;
    }

    /** The business day now, in Vietnam: what "past the day" is counted against. */
    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }

    /**
     * Opens and closes the alerts of {@code salesOrderId} to match its facts now. Idempotent: run twice, the second
     * changes nothing.
     *
     * <p>Joins the caller's transaction: the listener's own ({@code @ApplicationModuleListener} is REQUIRES_NEW
     * already) or the one the sweep opens per order. A REQUIRES_NEW here would hold a second pooled connection for the
     * whole recompute while the caller's sat idle.</p>
     */
    @Transactional
    public Recomputed recompute(UUID salesOrderId) {
        Objects.requireNonNull(salesOrderId, "salesOrderId");
        alerts.lockSalesOrder(salesOrderId);
        Order order = orders.findById(new OrderId(salesOrderId)).orElse(null);
        List<DelayAlert> open = alerts.openOf(salesOrderId);
        if (order == null) {
            // Gone: nothing holds for it any more.
            return new Applying(salesOrderId, null, Map.of(), Map.of(), Map.of(), open, Set.of()).run();
        }
        List<ProductionOrder> production = productionOrders.ofSalesOrder(salesOrderId);
        Map<UUID, LocalDate> expected = query.expectedCompletionDates(salesOrderId);
        List<DeliveryBatch> schedule = order.paidByContract()
                ? contracts.findLiveByOrderId(salesOrderId).map(SalesContract::schedule).orElse(List.of())
                : List.of();
        boolean shortageFollowed = SHORTAGE_FOLLOWED.contains(order.status()) && !schedule.isEmpty();
        boolean deliveryFollowed = DELIVERY_FOLLOWED.contains(order.status()) && !schedule.isEmpty();
        Map<UUID, Map<UUID, Integer>> lacking = shortageFollowed ? productionOrderService.lackingByBatch(salesOrderId)
                : Map.of();
        Set<UUID> handedOver = DelayAlertEvaluator.handedOver(schedule, settledCartons(order));

        List<DelayAlertEvaluator.Production> productions = production.stream()
                .map(p -> new DelayAlertEvaluator.Production(p.id(), p.status(), p.needByDate(), expected.get(p.id())))
                .toList();
        List<DelayAlertEvaluator.Batch> batches = schedule.stream()
                .map(b -> new DelayAlertEvaluator.Batch(b.id(), b.windowStart(), b.windowEnd(),
                        lacking.getOrDefault(b.id(), Map.of()).values().stream().mapToInt(Integer::intValue).sum(),
                        handedOver.contains(b.id())))
                .toList();
        DelayAlertEvaluator.Facts facts = new DelayAlertEvaluator.Facts(productions, batches, shortageFollowed,
                deliveryFollowed, leadDays(production));
        Set<DelayAlertEvaluator.Condition> holding = DelayAlertEvaluator.conditions(facts, today());
        Map<UUID, ProductionOrder> productionById = production.stream()
                .collect(Collectors.toMap(ProductionOrder::id, p -> p));
        Map<UUID, DeliveryBatch> batchById = schedule.stream().collect(Collectors.toMap(DeliveryBatch::id, b -> b));
        return new Applying(salesOrderId, order, productionById, batchById, expected, open, holding).run();
    }

    /** Writes what the evaluator decided and announces each alert this recompute was the one to open. */
    private final class Applying {

        private final UUID salesOrderId;
        private final Order order;
        private final Map<UUID, ProductionOrder> production;
        private final Map<UUID, DeliveryBatch> batches;
        private final Map<UUID, LocalDate> expected;
        private final List<DelayAlert> open;
        private final Set<DelayAlertEvaluator.Condition> holding;

        Applying(UUID salesOrderId, Order order, Map<UUID, ProductionOrder> production,
                 Map<UUID, DeliveryBatch> batches, Map<UUID, LocalDate> expected, List<DelayAlert> open,
                 Set<DelayAlertEvaluator.Condition> holding) {
            this.salesOrderId = salesOrderId;
            this.order = order;
            this.production = production;
            this.batches = batches;
            this.expected = expected;
            this.open = open;
            this.holding = holding;
        }

        Recomputed run() {
            DelayAlertEvaluator.Changes changes = DelayAlertEvaluator.reconcile(open, holding);
            Instant now = clock.instant();
            int closed = 0;
            for (DelayAlert alert : changes.toClose()) {
                if (alerts.close(alert.id(), now)) {
                    closed++;
                    log.info("Delay alert {} {} closed: its condition no longer holds", alert.type(), alert.subjectId());
                }
            }
            int opened = 0;
            for (DelayAlertEvaluator.Condition condition : changes.toOpen()) {
                DelayAlert alert = DelayAlert.open(Identifiers.newId(), condition, salesOrderId, now);
                if (alerts.open(alert)) {
                    opened++;
                    events.publishEvent(announcement(alert));
                    log.info("Delay alert {} opened on {} of order {}", alert.type(), alert.subjectId(),
                            order.orderNumber().value());
                }
            }
            return new Recomputed(opened, closed);
        }

        private DelayAlertOpened announcement(DelayAlert alert) {
            ProductionOrder made = alert.productionOrderId() == null ? null : production.get(alert.productionOrderId());
            DeliveryBatch batch = alert.deliveryBatchId() == null ? null : batches.get(alert.deliveryBatchId());
            LocalDate due = switch (alert.type()) {
                case AT_RISK, PRODUCTION_LATE -> made.needByDate();
                case PO_NEEDED -> batch.windowStart();
                case DELIVERY_LATE -> batch.windowEnd();
            };
            return new DelayAlertOpened(alert.id(), alert.type().name(), alert.salesOrderId(),
                    order.orderNumber().value(), alert.productionOrderId(), made == null ? null : made.code(),
                    alert.deliveryBatchId(), batch == null ? null : batch.seq(),
                    made != null ? made.receivingWarehouseId() : order.shippingWarehouseId(), due,
                    alert.type() == DelayAlertType.AT_RISK ? expected.get(made.id()) : null, alert.openedAt());
        }
    }

    /**
     * BR-30, B44: PO_NEEDED counts with the standard lead time of the producer that makes for the order - the longest of
     * those its production orders went to - and with the configured default (14 days) while it has none: no producer is
     * chosen for a batch nothing was drawn up for yet.
     */
    private int leadDays(List<ProductionOrder> production) {
        return production.stream().filter(p -> p.status() != ProductionOrderStatus.CANCELLED)
                .map(ProductionOrder::producerId).distinct()
                .map(producers::findById).flatMap(java.util.Optional::stream)
                .mapToInt(Producer::standardLeadDays).max().orElse(defaultLeadDays);
    }

    /** Per carton line, the cartons handed over (and not come back) or closed: what of the line no batch owes. */
    private static Map<UUID, Integer> settledCartons(Order order) {
        Map<UUID, Integer> settled = new HashMap<>();
        for (OrderLine line : order.lines()) {
            if (line.cartons() == null || line.unitsPerCarton() == null || line.unitsPerCarton() <= 0) {
                continue;
            }
            int units = line.quantity() - order.unshippedUnits(line.id());
            settled.put(line.id(), units / line.unitsPerCarton());
        }
        return settled;
    }

    // ---- the sweep --------------------------------------------------------------------------------------------------

    /** The sales orders the nightly sweep recomputes: followed ones, with an open production order or an open alert. */
    @Transactional(readOnly = true)
    public List<UUID> sweepCandidates() {
        Set<String> statuses = new java.util.TreeSet<>();
        SHORTAGE_FOLLOWED.forEach(status -> statuses.add(status.name()));
        DELIVERY_FOLLOWED.forEach(status -> statuses.add(status.name()));
        return alerts.salesOrdersToSweep(statuses);
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /**
     * Alerts for the order page and the list (step 49), newest first; a warehouse-bound caller sees those whose
     * warehouse is one of theirs.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductionScheduleQuery.AlertView> list(UUID salesOrderId, DelayAlertType type,
                                                                boolean openOnly, int page, int size) {
        Pageable pageable = Pages.of(page, size);
        ProductionScheduleQuery.AlertPage found = query.alerts(salesOrderId, type, openOnly,
                WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null), pageable.getPageSize(),
                pageable.getOffset());
        return PageResponse.of(found.items(), page, size, found.total());
    }

    /** The open alerts on these production orders and batches. */
    @Transactional(readOnly = true)
    public List<DelayAlert> openOn(java.util.Collection<UUID> subjectIds) {
        return new ArrayList<>(alerts.openOn(subjectIds));
    }
}
