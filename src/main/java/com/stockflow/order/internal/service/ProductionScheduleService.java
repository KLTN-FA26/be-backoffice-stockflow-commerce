package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.DelayAlert;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.order.internal.domain.ProductionScheduleQuery;
import com.stockflow.order.internal.domain.ProductionScheduleQuery.ScheduledProduction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The production schedule (4A-7, kltn-docs 17 §4.12 step 47): a screen that follows production done outside the
 * system, not a plan of anyone's capacity.
 *
 * <ul>
 *   <li><b>Production orders still open</b> (DRAFT to PARTIALLY_RECEIVED), by the day the goods are needed: producer,
 *       order and delivery batch with its window, receiving warehouse, cartons ordered and received, status, expected
 *       completion day and the delay flags - its own (AT_RISK, PRODUCTION_LATE) and its batch's DELIVERY_LATE.</li>
 *   <li><b>Short delivery batches</b> no open production order covers - where the coordinator starts drawing one up
 *       (§4.3 step 6): every batch of a confirmed order of a contract that still lacks cartons once held stock and open
 *       production orders are counted, with what each line lacks and the batch's flags (PO_NEEDED,
 *       DELIVERY_LATE).</li>
 * </ul>
 *
 * <p>Filters: producer (production orders only - a short batch has no producer yet), warehouse (receiving, or the
 * order's shipping warehouse), a range of days (the need-by day, or the window's first day; a batch with no window yet
 * passes only when no range is asked for) and only what carries an open alert. A warehouse-bound caller (the warehouse
 * manager) sees only their warehouses (step 47: "Quản lý kho chỉ thấy kho mình").</p>
 *
 * <p>Read live, not from a stored projection: the short batches come from the same count a production order is drawn up
 * from ({@link ProductionOrderService#lackingByBatch}), so the screen and the draw-up never disagree. The cost is one
 * count per followed order, bounded by the orders waiting for production.</p>
 */
@Service
@Transactional(readOnly = true)
public class ProductionScheduleService {

    /** A delay flag on a schedule line. */
    public record Flag(UUID alertId, DelayAlertType type, Instant openedAt) {
    }

    /** An open production order on the schedule. */
    public record Entry(ScheduledProduction production, String warehouseCode, List<Flag> alerts) {
    }

    /** What one line of a short batch lacks, in cartons. */
    public record ShortLine(UUID orderLineId, String sku, int cartons) {
    }

    /** A delivery batch that lacks cartons no open production order brings. */
    public record ShortBatch(UUID salesOrderId, String orderNumber, UUID customerId, UUID shippingWarehouseId,
                             String warehouseCode, UUID deliveryBatchId, int seq, LocalDate windowStart,
                             LocalDate windowEnd, int shortCartons, List<ShortLine> lines, List<Flag> alerts) {
    }

    /** The schedule: open production orders by need-by day, then the short batches by window. */
    public record Schedule(List<Entry> productionOrders, List<ShortBatch> shortBatches) {
    }

    private final ProductionScheduleQuery query;
    private final ProductionOrderService productionOrders;
    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final WarehouseDirectory warehouses;
    private final DelayAlertService alerts;

    ProductionScheduleService(ProductionScheduleQuery query, ProductionOrderService productionOrders,
                              OrderRepository orders, SalesContractRepository contracts, WarehouseDirectory warehouses,
                              DelayAlertService alerts) {
        this.query = query;
        this.productionOrders = productionOrders;
        this.orders = orders;
        this.contracts = contracts;
        this.warehouses = warehouses;
        this.alerts = alerts;
    }

    /**
     * @throws BusinessException {@code VALIDATION_FAILED} when {@code from} is after {@code to}
     */
    public Schedule schedule(UUID producerId, UUID warehouseId, LocalDate from, LocalDate to, boolean alertsOnly) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from is after to");
        }
        Collection<UUID> scope = WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null);
        List<ScheduledProduction> open = query.openProductionOrders(producerId, warehouseId, from, to, scope);
        List<ShortBatch> shortBatches = shortBatches(warehouseId, from, to, scope);

        Set<UUID> subjects = new LinkedHashSet<>();
        open.forEach(p -> {
            subjects.add(p.productionOrderId());
            subjects.add(p.deliveryBatchId());
        });
        shortBatches.forEach(b -> subjects.add(b.deliveryBatchId()));
        Map<UUID, List<Flag>> flags = new HashMap<>();
        for (DelayAlert alert : alerts.openOn(subjects)) {
            flags.computeIfAbsent(alert.subjectId(), id -> new ArrayList<>())
                    .add(new Flag(alert.id(), alert.type(), alert.openedAt()));
        }
        Map<UUID, String> codes = new HashMap<>();

        List<Entry> entries = new ArrayList<>();
        for (ScheduledProduction production : open) {
            List<Flag> own = new ArrayList<>(flags.getOrDefault(production.productionOrderId(), List.of()));
            flags.getOrDefault(production.deliveryBatchId(), List.of()).stream()
                    .filter(flag -> flag.type() == DelayAlertType.DELIVERY_LATE).forEach(own::add);
            if (!alertsOnly || !own.isEmpty()) {
                entries.add(new Entry(production, code(codes, production.receivingWarehouseId()), own));
            }
        }
        List<ShortBatch> batches = new ArrayList<>();
        for (ShortBatch batch : shortBatches) {
            List<Flag> own = flags.getOrDefault(batch.deliveryBatchId(), List.of());
            if (!alertsOnly || !own.isEmpty()) {
                batches.add(new ShortBatch(batch.salesOrderId(), batch.orderNumber(), batch.customerId(),
                        batch.shippingWarehouseId(), code(codes, batch.shippingWarehouseId()), batch.deliveryBatchId(),
                        batch.seq(), batch.windowStart(), batch.windowEnd(), batch.shortCartons(), batch.lines(),
                        List.copyOf(own)));
            }
        }
        return new Schedule(entries, batches);
    }

    /** Every short batch of the confirmed orders of a contract in scope, by window (none last), order, number. */
    private List<ShortBatch> shortBatches(UUID warehouseId, LocalDate from, LocalDate to, Collection<UUID> scope) {
        List<String> statuses = DelayAlertService.SHORTAGE_FOLLOWED.stream().map(OrderStatus::name).toList();
        List<ShortBatch> found = new ArrayList<>();
        for (ProductionScheduleQuery.FollowedOrder followed : query.ordersIn(statuses, warehouseId, scope)) {
            Map<UUID, Map<UUID, Integer>> lacking = productionOrders.lackingByBatch(followed.salesOrderId());
            if (lacking.values().stream().noneMatch(lines -> lines.values().stream().anyMatch(c -> c > 0))) {
                continue;
            }
            Order order = orders.findById(new OrderId(followed.salesOrderId())).orElse(null);
            SalesContract contract = contracts.findLiveByOrderId(followed.salesOrderId()).orElse(null);
            if (order == null || contract == null) {
                continue;
            }
            Map<UUID, String> skus = new HashMap<>();
            order.lines().forEach(line -> skus.put(line.id(), line.sku().code()));
            for (DeliveryBatch batch : contract.schedule()) {
                Map<UUID, Integer> lines = lacking.getOrDefault(batch.id(), Map.of());
                int cartons = lines.values().stream().mapToInt(Integer::intValue).sum();
                if (cartons <= 0 || !within(batch.windowStart(), from, to)) {
                    continue;
                }
                List<ShortLine> shortLines = order.lines().stream().map(OrderLine::id)
                        .filter(id -> lines.getOrDefault(id, 0) > 0)
                        .map(id -> new ShortLine(id, skus.get(id), lines.get(id))).toList();
                found.add(new ShortBatch(followed.salesOrderId(), followed.orderNumber(), followed.customerId(),
                        followed.shippingWarehouseId(), null, batch.id(), batch.seq(), batch.windowStart(),
                        batch.windowEnd(), cartons, shortLines, List.of()));
            }
        }
        found.sort(Comparator.comparing(ShortBatch::windowStart, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ShortBatch::orderNumber).thenComparingInt(ShortBatch::seq));
        return found;
    }

    private static boolean within(LocalDate day, LocalDate from, LocalDate to) {
        if (day == null) {
            return from == null && to == null;
        }
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }

    private String code(Map<UUID, String> codes, UUID warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        return codes.computeIfAbsent(warehouseId, id -> warehouses.codeOf(id).orElse(null));
    }
}
