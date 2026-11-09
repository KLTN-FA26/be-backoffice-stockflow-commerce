package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shipping warehouse of an order of a contract and its stock held there (4B-7; kltn-docs 17 §4.2 steps 2-3, BR-21,
 * BR-22; 20 §5.6, BR-01, BR-17; team plan QD-4).
 *
 * <ul>
 *   <li><b>Options</b> (step 2): for each active warehouse, the whole cartons of each line's SKU available to promise
 *       there. Warehouses holding every line in full come first, then those holding more. The projected free space (m³)
 *       is not worked out yet: no warehouse capacity table exists (team plan, "Bảng sức chứa kho").</li>
 *   <li><b>Choose</b>: Sales picks one; the order then holds there, by SKU, as many whole cartons of each line as are
 *       available - all, or some (k &lt; N) - with no deadline. What is missing is not refused: it is shown per delivery
 *       batch, the earliest batch served first, and topped up when stock arrives. The warehouse changes only while
 *       nothing is held and no production order of the order has been sent to a producer (BR-22); the production
 *       orders not yet sent move with it.</li>
 *   <li><b>Top up</b>: when goods are put away, every order waiting for stock at a warehouse - CONFIRMED, or
 *       AWAITING_STOCK once a production order was sent for it (4A-6) - holds what it can, the earliest signed
 *       first.</li>
 *   <li><b>Release</b> needs every carton held at the shipping warehouse (until 4B-8 releases batch by batch).</li>
 *   <li><b>Next batch</b> (4A-6, BR-04): whether the batch the order delivers next is held in full - what decides,
 *       with a production order sent for it, that the order waits for stock.</li>
 * </ul>
 *
 * <p>Locks: the order row first, then the SKUs' advisory locks (sorted, {@code prepareReservation}), as checkout and
 * release take them. Held quantities are read from inventory under those locks: a line's holds are its reservation ids
 * still HELD.</p>
 */
@Service
@Transactional
public class ShippingWarehouseService {

    private static final Logger log = LoggerFactory.getLogger(ShippingWarehouseService.class);

    /** One warehouse an order may ship from, and what of each line it has available to promise now. */
    public record Option(UUID warehouseId, String warehouseCode, String warehouseName, boolean holdsEveryLine,
                         int availableCartons, List<LineAvailability> lines) {
    }

    /** A line's SKU at one warehouse: its cartons, and how many are available to promise there. */
    public record LineAvailability(UUID lineId, String sku, int cartons, int availableCartons) {
    }

    /** The warehouses an order may ship from, best first, and the one chosen, if any. */
    public record Options(UUID orderId, UUID chosenWarehouseId, boolean capacityComputed, List<Option> warehouses) {
    }

    /** How much of a line is held: in cartons, what it needs, what is held, what is short. */
    public record LineCoverage(UUID lineId, String sku, int cartons, int heldCartons, int shortCartons) {
    }

    /** A delivery batch's lines and their cover, the earliest batch served first (17 §4.2 step 3). */
    public record BatchCoverage(int seq, LocalDate windowStart, LocalDate windowEnd, boolean fullyHeld,
                                List<LineCoverage> lines) {
    }

    /** Where an order's stock stands at its shipping warehouse: per line, and per delivery batch. */
    public record Coverage(UUID orderId, UUID warehouseId, String warehouseCode, boolean fullyHeld,
                           List<LineCoverage> lines, List<BatchCoverage> batches) {
    }

    /** The delivery batch an order delivers next, and whether every carton of it is held (4A-6). */
    public record NextBatch(UUID batchId, int seq, boolean fullyHeld) {
    }

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final WarehouseDirectory warehouses;
    private final SalesContractRepository contracts;
    private final Clock clock;
    private final TransactionTemplate eachOrder;
    private final com.stockflow.order.internal.domain.ProductionOrderRepository productionOrders;

    ShippingWarehouseService(OrderRepository orders, InventoryService inventory, WarehouseDirectory warehouses,
                             SalesContractRepository contracts, Clock clock, PlatformTransactionManager transactions,
                             com.stockflow.order.internal.domain.ProductionOrderRepository productionOrders) {
        this.productionOrders = productionOrders;
        this.orders = orders;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.contracts = contracts;
        this.clock = clock;
        this.eachOrder = new TransactionTemplate(transactions);
        this.eachOrder.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The warehouses the order may ship from (17 §4.2 step 2), best first.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED} for an order not of a
     *         contract
     */
    @Transactional(readOnly = true)
    public Options options(UUID orderId) {
        Order order = read(orderId);
        requireContract(order);
        List<OrderLine> lines = cartonLines(order);
        Map<String, Map<String, Integer>> atp = new HashMap<>();
        for (OrderLine line : lines) {
            atp.computeIfAbsent(line.sku().code(), sku -> inventory.availableToPromiseByWarehouse(new Sku(sku)));
        }
        // What the order holds already is available to it at its shipping warehouse, though no longer to others.
        Map<UUID, Integer> own = heldUnitsByLine(order);
        String chosen = order.shippingWarehouseId() == null ? null
                : warehouses.codeOf(order.shippingWarehouseId()).orElse(null);
        List<Option> options = new ArrayList<>();
        for (WarehouseDirectory.Warehouse warehouse : warehouses.active()) {
            List<LineAvailability> availability = lines.stream().map(line -> new LineAvailability(line.id(),
                    line.sku().code(), line.cartons(), (atp.get(line.sku().code()).getOrDefault(warehouse.code(), 0)
                    + (warehouse.code().equals(chosen) ? own.getOrDefault(line.id(), 0) : 0))
                    / line.unitsPerCarton())).toList();
            boolean every = availability.stream().allMatch(a -> a.availableCartons() >= a.cartons());
            int cartons = availability.stream().mapToInt(a -> Math.min(a.availableCartons(), a.cartons())).sum();
            options.add(new Option(warehouse.id(), warehouse.code(), warehouse.name(), every, cartons, availability));
        }
        // C3: a warehouse holding every line first, then the one holding more, then by code for a stable order.
        options.sort(Comparator.comparing(Option::holdsEveryLine).reversed()
                .thenComparing(Comparator.comparingInt(Option::availableCartons).reversed())
                .thenComparing(Option::warehouseCode));
        return new Options(orderId, order.shippingWarehouseId(), false, options);
    }

    /**
     * Sales chooses the warehouse the order ships from (17 §4.2 steps 2-3): the order holds there as many whole
     * cartons of each line as are available, now. Another warehouse is refused while stock is held, or once a
     * production order of the order was sent (it delivers to the warehouse it names); production orders not yet sent are
     * received at the new one.
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code WAREHOUSE_NOT_FOUND} for no active warehouse of that id;
     *         {@code ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED}; {@code ORDER_SHIPPING_WAREHOUSE_LOCKED} (BR-22)
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "order-shipping-warehouse", resourceId = "#orderId")
    public Coverage choose(UUID orderId, UUID warehouseId, UUID by) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        WarehouseDirectory.Warehouse warehouse = warehouses.active().stream()
                .filter(w -> w.id().equals(warehouseId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "No active warehouse " + warehouseId));
        boolean holdsStock = heldUnitsByLine(order).values().stream().anyMatch(units -> units > 0);
        List<com.stockflow.order.internal.domain.ProductionOrder> production = productionOrders.ofSalesOrder(orderId);
        if (order.shippingWarehouseId() != null && !warehouse.id().equals(order.shippingWarehouseId())
                && production.stream().anyMatch(com.stockflow.order.internal.domain.ProductionOrder::sentAndOpen)) {
            // 17 §4.2 step 2, BR-22: a production order sent to a producer delivers to the warehouse it names.
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED, ("Order %s has a production order "
                    + "sent to be delivered to its shipping warehouse").formatted(order.orderNumber()));
        }
        boolean changed = order.chooseShippingWarehouse(warehouse.id(), holdsStock, by, clock.instant());
        if (changed) {
            log.info("Order {} ships from {}", order.orderNumber(), warehouse.code());
        }
        holdWhatIsAvailable(order, warehouse.code());
        orders.save(order);
        if (changed) {
            // Those not sent yet are received at the new warehouse (17 §4.2 step 2) - after the order's row says it,
            // which the production order's own check reads (check_production_order).
            for (com.stockflow.order.internal.domain.ProductionOrder draft : production) {
                if (draft.receiveAt(warehouse.id())) {
                    productionOrders.save(draft);
                }
            }
        }
        return coverageOf(order, warehouse.code());
    }

    /**
     * Stock arrived (goods put away): every order of a contract waiting at a warehouse - CONFIRMED or AWAITING_STOCK -
     * holds what it can now, the earliest signed first (17 §4.2 step 3: "hàng nhập về sau bù đợt sớm nhất còn thiếu";
     * §4.3 step 11). One transaction per order, so one order's failure does not hold back the others. Holding more
     * changes no status: an order waiting for stock is released once every carton is held, as a confirmed one is
     * ({@code OrderStatus} javadoc).
     *
     * @return how many orders held more
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int topUpWaiting() {
        return topUpWaiting(null);
    }

    /**
     * The same for one SKU: stock of it came free (a hold released, a cancellation). Only orders short of it are
     * locked; {@code sku} null: any SKU (goods put away - the event does not say which).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int topUpWaiting(String sku) {
        int toppedUp = 0;
        for (UUID orderId : orders.waitingForStockAtShippingWarehouse()) {
            if (!shortOf(orderId, sku)) {
                continue;
            }
            try {
                Boolean more = eachOrder.execute(status -> topUp(orderId));
                if (Boolean.TRUE.equals(more)) {
                    toppedUp++;
                }
            } catch (RuntimeException e) {
                log.warn("Order {}: topping up its held stock failed; the next arrival tries again", orderId, e);
            }
        }
        return toppedUp;
    }

    /** Whether the order is short of {@code sku} (or of anything, for null) - read without a lock, to skip the rest. */
    private boolean shortOf(UUID orderId, String sku) {
        Order order = orders.findById(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return false;
        }
        Map<UUID, Integer> held = heldUnitsByLine(order);
        return cartonLines(order).stream().filter(line -> sku == null || line.sku().code().equals(sku))
                .anyMatch(line -> line.cartons() > held.getOrDefault(line.id(), 0) / line.unitsPerCarton());
    }

    private boolean topUp(UUID orderId) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.AWAITING_STOCK
                || order.shippingWarehouseId() == null) {
            return false;
        }
        String code = warehouses.codeOf(order.shippingWarehouseId()).orElse(null);
        if (code == null) {
            return false;
        }
        boolean more = holdWhatIsAvailable(order, code);
        if (more) {
            orders.save(order);
        }
        return more;
    }

    /**
     * Holds, at {@code warehouseCode}, the cartons each line still lacks - as many as are available. Each attempt has a
     * request id of its own (the line, how much it held before, the warehouse), so a retried request holds once and a
     * later arrival holds again.
     *
     * @return whether anything more was held
     */
    private boolean holdWhatIsAvailable(Order order, String warehouseCode) {
        Map<UUID, Integer> held = heldUnitsByLine(order);
        List<OrderLine> lines = cartonLines(order).stream()
                .filter(line -> line.cartons() > held.getOrDefault(line.id(), 0) / line.unitsPerCarton())
                .toList();
        if (lines.isEmpty()) {
            return false;
        }
        // Only the SKUs still short are locked (sorted), so an order already held in full holds nothing up.
        Set<Sku> skus = new LinkedHashSet<>();
        lines.forEach(line -> skus.add(line.sku()));
        inventory.prepareReservation(skus);
        boolean more = false;
        for (OrderLine line : lines) {
            int heldCartons = held.getOrDefault(line.id(), 0) / line.unitsPerCarton();
            int missing = line.cartons() - heldCartons;
            // One request per attempt: the line's holds so far (released ones stay on the line) and the warehouse - a
            // retried attempt holds once; the next attempt, after a hold or a release by hand, is a new request.
            UUID requestId = UUID.nameUUIDFromBytes(("hold:%s:%d:%s".formatted(line.id(),
                    line.reservationIds().size(), warehouseCode)).getBytes(StandardCharsets.UTF_8));
            HoldStockResult result = inventory.holdAtWarehouse(new HoldStockCommand(requestId, order.id().value(),
                    line.sku(), warehouseCode, missing, line.unitsPerCarton()));
            if (!result.reservationIds().isEmpty()) {
                order.holdMore(line.id(), result.reservationIds());
                more = true;
            }
        }
        return more;
    }

    /**
     * 17 §4.2 step 5: released once every carton of every line is held at its shipping warehouse.
     *
     * @throws BusinessException {@code ORDER_STOCK_NOT_HELD} naming the lines short
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireFullyHeld(Order order) {
        Map<UUID, Integer> held = heldUnitsByLine(order);
        List<String> short_ = cartonLines(order).stream()
                .filter(line -> held.getOrDefault(line.id(), 0) / line.unitsPerCarton() < line.cartons())
                .map(line -> "%s (%d of %d cartons)".formatted(line.sku().code(),
                        held.getOrDefault(line.id(), 0) / line.unitsPerCarton(), line.cartons()))
                .toList();
        if (!short_.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Order %s is not all held at its shipping "
                    + "warehouse: %s").formatted(order.orderNumber(), String.join(", ", short_)));
        }
    }

    /**
     * 4A-6 (17 §4.2 step 5 "đủ hàng / thiếu hàng ở trên xét cho đợt giao kế tiếp", BR-04): the delivery batch the order
     * delivers next, and whether every carton of it is held at the shipping warehouse - held cartons counting for the
     * earliest batch first, as {@link #coverage} counts them. An order not released has handed nothing over, so its next
     * batch is its earliest one; telling the next batch after a first handover is 4B-8's (release batch by batch). Read
     * under the caller's lock on the order, in its transaction.
     *
     * @return empty for an order with no live contract, or none with a delivery schedule
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public java.util.Optional<NextBatch> nextBatch(Order order) {
        DeliveryBatch first = contracts.findLiveByOrderId(order.id().value()).stream()
                .flatMap(contract -> contract.schedule().stream())
                .min(Comparator.comparingInt(DeliveryBatch::seq)).orElse(null);
        if (first == null) {
            return java.util.Optional.empty();
        }
        boolean held = coverageOf(order, null).batches().stream().filter(batch -> batch.seq() == first.seq())
                .findFirst().map(BatchCoverage::fullyHeld).orElse(false);
        return java.util.Optional.of(new NextBatch(first.id(), first.seq(), held));
    }

    /**
     * Where the order's stock stands at its shipping warehouse: per line and per delivery batch, the earliest batch
     * served first (17 §4.2 step 3, BR-29).
     */
    @Transactional(readOnly = true)
    public Coverage coverage(UUID orderId) {
        Order order = read(orderId);
        requireContract(order);
        String code = order.shippingWarehouseId() == null ? null
                : warehouses.codeOf(order.shippingWarehouseId()).orElse(null);
        return coverageOf(order, code);
    }

    private Coverage coverageOf(Order order, String warehouseCode) {
        Map<UUID, Integer> held = securedUnitsByLine(order);
        List<OrderLine> lines = cartonLines(order);
        Map<UUID, Integer> heldCartons = new HashMap<>();
        List<LineCoverage> perLine = new ArrayList<>();
        for (OrderLine line : lines) {
            int cartons = Math.min(line.cartons(), held.getOrDefault(line.id(), 0) / line.unitsPerCarton());
            heldCartons.put(line.id(), cartons);
            perLine.add(new LineCoverage(line.id(), line.sku().code(), line.cartons(), cartons,
                    line.cartons() - cartons));
        }
        Map<UUID, String> skuOf = new HashMap<>();
        lines.forEach(line -> skuOf.put(line.id(), line.sku().code()));
        List<BatchCoverage> batches = new ArrayList<>();
        SalesContract contract = contracts.findLiveByOrderId(order.id().value()).orElse(null);
        if (contract != null) {
            Map<UUID, Integer> left = new HashMap<>(heldCartons);
            for (DeliveryBatch batch : contract.schedule().stream()
                    .sorted(Comparator.comparingInt(DeliveryBatch::seq)).toList()) {
                List<LineCoverage> batchLines = new ArrayList<>();
                for (DeliveryBatch.Line line : batch.lines()) {
                    int available = left.getOrDefault(line.orderLineId(), 0);
                    int takes = Math.min(available, line.cartons());
                    left.put(line.orderLineId(), available - takes);
                    batchLines.add(new LineCoverage(line.orderLineId(), skuOf.get(line.orderLineId()), line.cartons(),
                            takes, line.cartons() - takes));
                }
                batches.add(new BatchCoverage(batch.seq(), batch.windowStart(), batch.windowEnd(),
                        batchLines.stream().allMatch(l -> l.shortCartons() == 0), batchLines));
            }
        }
        return new Coverage(order.id().value(), order.shippingWarehouseId(), warehouseCode,
                perLine.stream().allMatch(l -> l.shortCartons() == 0), perLine, batches);
    }

    /** Units held for each line: its reservation ids still HELD (holds at the warehouse, or allocations after release). */
    private Map<UUID, Integer> heldUnitsByLine(Order order) {
        return unitsByLine(order, EnumSet.of(HoldView.Status.HELD));
    }

    /**
     * Units each line no longer lacks: held, or already picked for it (CONSUMED). The coverage counts both - goods picked
     * for batch 1 of an order released batch by batch are no longer held, but batch 1 does not lack them, and counting
     * them as short would ask for production of what is already on its way to the customer (4A-7 review).
     */
    private Map<UUID, Integer> securedUnitsByLine(Order order) {
        return unitsByLine(order, EnumSet.of(HoldView.Status.HELD, HoldView.Status.CONSUMED));
    }

    private Map<UUID, Integer> unitsByLine(Order order, Set<HoldView.Status> counted) {
        Map<UUID, UUID> lineOf = new HashMap<>();
        order.lines().forEach(line -> line.reservationIds().forEach(id -> lineOf.put(id, line.id())));
        Map<UUID, Integer> units = new HashMap<>();
        if (lineOf.isEmpty()) {
            return units;
        }
        for (HoldView hold : inventory.holds(lineOf.keySet())) {
            if (counted.contains(hold.status())) {
                units.merge(lineOf.get(hold.reservationId()), hold.quantity(), Integer::sum);
            }
        }
        return units;
    }

    /** The order's lines counted in cartons - every line of an order made from a quote (4B-2). */
    private static List<OrderLine> cartonLines(Order order) {
        return order.lines().stream()
                .filter(line -> line.cartons() != null && line.unitsPerCarton() != null && line.unitsPerCarton() > 0)
                .toList();
    }

    private Order read(UUID orderId) {
        return orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private static void requireContract(Order order) {
        if (!order.paidByContract()) {
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED, ("Order %s is not of a signed "
                    + "contract: it has no shipping warehouse to choose").formatted(order.orderNumber()));
        }
    }
}
