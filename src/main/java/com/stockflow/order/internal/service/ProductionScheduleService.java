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
import com.stockflow.order.internal.domain.OrderLine;
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
 *       (§4.3 step 6): every batch of an order of a contract whose shortage is followed (confirmed, and on past release
 *       while later batches are short) that still lacks cartons once held and picked stock and open production orders
 *       are counted, with what each line lacks and the batch's flags (PO_NEEDED, DELIVERY_LATE).</li>
 * </ul>
 *
 * <p>Filters: producer (production orders only - a short batch has no producer yet), warehouse (receiving, or the
 * order's shipping warehouse), a range of days (the need-by day, or the window's first day; a batch with no window yet
 * passes only when no range is asked for) and only what carries an open alert. A warehouse-bound caller (the warehouse
 * manager) sees only their warehouses (step 47: "Quản lý kho chỉ thấy kho mình").</p>
 *
 * <p>Read live, not from a stored projection: the short batches come from the same count a production order is drawn up
 * from ({@link ProductionOrderService#shortage}), so the screen and the draw-up never disagree. That count is a few
 * statements per order, so it runs only for candidates SQL already narrowed (a batch in the range of days, with an
 * alert when only those are asked for), the most urgent first, and at most {@code limit} of them; the open production
 * orders are cut at {@code limit} too. {@link Schedule#truncated} says a list was cut, so the screen asks to narrow the
 * filters rather than showing a partial list as whole.</p>
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

    /** The schedule: open production orders by need-by day, then the short batches by window; truncated when cut. */
    public record Schedule(List<Entry> productionOrders, List<ShortBatch> shortBatches, boolean truncated) {
    }

    /** How many rows each list holds when no limit is asked for. */
    public static final int DEFAULT_LIMIT = 100;
    /** The most each list may hold. */
    public static final int MAX_LIMIT = 200;

    private final ProductionScheduleQuery query;
    private final ProductionOrderService productionOrders;
    private final WarehouseDirectory warehouses;
    private final DelayAlertService alerts;

    ProductionScheduleService(ProductionScheduleQuery query, ProductionOrderService productionOrders,
                              WarehouseDirectory warehouses, DelayAlertService alerts) {
        this.query = query;
        this.productionOrders = productionOrders;
        this.warehouses = warehouses;
        this.alerts = alerts;
    }

    /** {@link #schedule(UUID, UUID, LocalDate, LocalDate, boolean, int)} with the default limit. */
    public Schedule schedule(UUID producerId, UUID warehouseId, LocalDate from, LocalDate to, boolean alertsOnly) {
        return schedule(producerId, warehouseId, from, to, alertsOnly, DEFAULT_LIMIT);
    }

    /**
     * @param limit the most rows each list holds, 1 to {@link #MAX_LIMIT}
     * @throws BusinessException {@code VALIDATION_FAILED} when {@code from} is after {@code to}, or the limit is out of
     *         range
     */
    public Schedule schedule(UUID producerId, UUID warehouseId, LocalDate from, LocalDate to, boolean alertsOnly,
                             int limit) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "from is after to");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
        }
        Collection<UUID> scope = WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null);
        List<ScheduledProduction> open = new ArrayList<>(query.openProductionOrders(producerId, warehouseId, from, to,
                scope, alertsOnly, limit + 1));
        boolean truncated = open.size() > limit;
        if (truncated) {
            open = new ArrayList<>(open.subList(0, limit));
        }
        Cut<ShortBatch> cut = shortBatches(warehouseId, from, to, scope, alertsOnly, limit);
        List<ShortBatch> shortBatches = cut.items();
        truncated |= cut.truncated();

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
        return new Schedule(entries, batches, truncated);
    }

    /** At most a limit of rows, and whether there were more. */
    private record Cut<T>(List<T> items, boolean truncated) {
    }

    /**
     * The short batches of the followed orders of a contract in scope, by window (none last), order, number: counted
     * for at most {@code limit} candidate orders, the most urgent first, and cut at {@code limit} batches.
     */
    private Cut<ShortBatch> shortBatches(UUID warehouseId, LocalDate from, LocalDate to, Collection<UUID> scope,
                                         boolean alertsOnly, int limit) {
        List<String> statuses = DelayAlertService.SHORTAGE_FOLLOWED.stream().map(OrderStatus::name).toList();
        List<ProductionScheduleQuery.FollowedOrder> candidates = query.ordersIn(statuses, warehouseId, scope, from, to,
                alertsOnly, limit + 1);
        boolean truncated = candidates.size() > limit;
        List<ShortBatch> found = new ArrayList<>();
        for (ProductionScheduleQuery.FollowedOrder followed : candidates.subList(0, Math.min(limit,
                candidates.size()))) {
            ProductionOrderService.Shortage shortage = productionOrders.shortage(followed.salesOrderId());
            if (shortage == null || shortage.lackingByBatch().values().stream()
                    .noneMatch(lines -> lines.values().stream().anyMatch(c -> c > 0))) {
                continue;
            }
            Map<UUID, Map<UUID, Integer>> lacking = shortage.lackingByBatch();
            Order order = shortage.order();
            Map<UUID, String> skus = new HashMap<>();
            order.lines().forEach(line -> skus.put(line.id(), line.sku().code()));
            for (DeliveryBatch batch : shortage.schedule()) {
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
        if (found.size() > limit) {
            return new Cut<>(List.copyOf(found.subList(0, limit)), true);
        }
        return new Cut<>(found, truncated);
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
