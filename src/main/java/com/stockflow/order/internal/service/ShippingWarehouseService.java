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
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
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
 *   <li><b>By delivery batch</b> (4B-8, 17 §4.2 step 3, BR-29): an order with a delivery schedule holds batch by batch,
 *       the earliest first - each hold is taken for one batch's cartons of one line and recorded as that batch's
 *       ({@link DeliveryBatchRecords}), so the batch is released with exactly its own holds. Orders keep topping up
 *       after their first release, for the batches still to come.</li>
 *   <li><b>Release</b> needs every carton of the batch released held at the shipping warehouse; an order without a
 *       schedule, every carton of every line.</li>
 *   <li><b>Next batch</b> (4A-6, BR-04): whether the batch the order delivers next - the earliest not released - is
 *       held in full: what decides, with a production order sent for it, that the order waits for stock.</li>
 *   <li><b>Goods that came back</b> (WF-05 fix plan, section 2): the units of a shipment the carrier brought back are
 *       held for their order again the moment they are stock to promise - released from quarantine and put away -
 *       before any other order is served, whether Sales have decided the delivery failure case or not. They are the
 *       customer's own goods: no other order's top-up takes them meanwhile, and a redelivery is released with exactly
 *       the holds taken for its case. A case cancelled frees them with the rest of the order.</li>
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

    /**
     * A delivery batch's lines and their cover, the earliest batch served first (17 §4.2 step 3); its window is the one in
     * force (17 BR-28), not necessarily the contract's.
     */
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
    private final DeliveryBatchRecords batchRecords;
    private final com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures;

    ShippingWarehouseService(OrderRepository orders, InventoryService inventory, WarehouseDirectory warehouses,
                             SalesContractRepository contracts, Clock clock, PlatformTransactionManager transactions,
                             com.stockflow.order.internal.domain.ProductionOrderRepository productionOrders,
                             DeliveryBatchRecords batchRecords,
                             com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures) {
        this.deliveryFailures = deliveryFailures;
        this.batchRecords = batchRecords;
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
        Order order = lockServingReturnedGoodsFirst(orderId);
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
     * Locks the order to hold stock for, after the goods that came back from a failed delivery of the same SKUs are held
     * for their own cases (WF-05 review F4): those cases are served before any other hold of the SKU, here as in
     * {@link #topUpWaiting(String)}. Without it, Sales choosing a warehouse for another order of the same customer, in
     * the window between the cartons leaving quarantine and the arrival listener running, would hold them for that
     * order, and the case could never be released.
     *
     * <p>Every order involved is locked in id order, then every SKU any of them holds is locked at once, sorted - the
     * order rows before the SKU locks, as everywhere, and no SKU lock taken after a later one. The order's own cases
     * are served too, when it has any.</p>
     *
     * <p>Known limit: the holds are by SKU at the warehouse, not by LPN. Goods of the SKU put away while a case waits
     * for its cartons in quarantine are held for that case, and the cartons themselves stay stock to promise until a
     * hold takes them; pinning the case to its own cartons is an open question (WF05-dfail report).</p>
     */
    private Order lockServingReturnedGoodsFirst(UUID orderId) {
        Order unlocked = orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        Set<String> codes = new java.util.TreeSet<>();
        unlocked.lines().forEach(line -> codes.add(line.sku().code()));
        Set<UUID> involved = new java.util.TreeSet<>(deliveryFailures.ordersWantingGoodsHeld(codes));
        involved.add(orderId);
        Map<UUID, Order> locked = new java.util.LinkedHashMap<>();
        for (UUID id : involved) {
            orders.findByIdForUpdate(new OrderId(id)).ifPresent(each -> locked.put(id, each));
        }
        Order order = locked.get(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId);
        }
        Set<Sku> skus = new java.util.TreeSet<>(Comparator.comparing(Sku::code));
        order.lines().forEach(line -> skus.add(line.sku()));
        locked.values().forEach(each -> skus.addAll(returnedSkus(each, wantingGoodsHeld(each))));
        inventory.prepareReservation(skus);
        boolean ownSaved = false;
        for (Order each : locked.values()) {
            if (holdReturnedGoods(each)) {
                orders.save(each);
                ownSaved |= each == order;
            }
        }
        // Saved once already: the order goes on from the row as written, read again under the lock it holds.
        return ownSaved ? orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow() : order;
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
     * The same for one SKU: stock of it came free (a hold released, a cancellation). Only orders short of it, and only
     * orders whose returned goods are of it, are locked; {@code sku} null: any SKU (goods put away - the event does not
     * say which).
     *
     * <p>The goods that came back from a failed delivery are held for their cases first, every case tried. If holding
     * any case's goods failed, the call stops there and fails (WF-05 review F4, N2): no ordinary order is topped up in
     * this run, since each top-up commits on its own and would take the free cartons the case is waiting for. A failed
     * top-up of an ordinary order is only logged: the next arrival serves it, and nothing else can take its place.</p>
     *
     * <p>What happens to the event then. The listener's publication stays incomplete in {@code event_publication}. It
     * is <em>not</em> retried while the application runs: nothing resubmits this listener's publications (the one
     * scheduled resubmission, notification's {@code ProductionOrderMailRetry}, serves production-order mail only), and
     * it is replayed at the next start when {@code republish-outstanding-events-on-restart} is on (it is in
     * {@code application.yml}, not in tests). The replay is safe - each hold has its request id. Meanwhile every later arrival tries the case again first; while
     * it keeps failing, each of those events stays incomplete too and the ordinary orders short of stock wait. That is
     * the price of never giving a case's goods to another order: a case that fails for good is a defect to fix, seen
     * in the log and in the registry.</p>
     *
     * @throws RuntimeException the first failure to hold a case's goods, after every case was tried and before any
     *         ordinary order was topped up
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int topUpWaiting(String sku) {
        int toppedUp = 0;
        RuntimeException caseFailed = null;
        // First the goods that came back from a failed delivery: they are held for their own order again before any
        // other order of that SKU is served (WF-05 fix plan, section 2) - the oldest case first.
        for (UUID orderId : deliveryFailures.ordersWantingGoodsHeld(sku == null ? null : List.of(sku))) {
            try {
                Boolean more = eachOrder.execute(status -> holdReturnedGoods(orderId));
                if (Boolean.TRUE.equals(more)) {
                    toppedUp++;
                }
            } catch (RuntimeException e) {
                log.warn("Order {}: holding the goods that came back failed; no other order is topped up in this run, "
                        + "and the event stays incomplete in the publication registry", orderId, e);
                if (caseFailed == null) {
                    caseFailed = e;
                }
            }
        }
        if (caseFailed != null) {
            // WF-05 review N2: topping up ordinary orders now would hand them the cartons the case is waiting for.
            throw caseFailed;
        }
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

    /**
     * WF-05 fix plan, section 2: the order's delivery failure cases that still want their goods held - not decided yet,
     * or to be delivered again and not released - hold, at the warehouse the goods came back to, as many whole cartons
     * of each line of the returned shipment as are available and not held for the case already. Under the order's row
     * lock, taken here; the SKUs' advisory locks next, sorted, as every hold takes them. Whatever the order's status:
     * it is ON_HOLD until Sales decide, and the goods wait for it all the same.
     *
     * <p>A redelivery is exactly what came back, never more (WF-05 review F14), and that is whole cartons of each line
     * (review N3, {@code DeliveryFailureCase.cartonsToHold}): a shipment leaves and comes back as whole cartons, and
     * the release allocates whole cartons only. A case is not opened otherwise, so nothing is ever held loose for one.</p>
     *
     * @return whether anything more was held
     */
    @Transactional
    public boolean holdReturnedGoods(UUID orderId) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return false;
        }
        boolean more = holdReturnedGoods(order);
        if (more) {
            orders.save(order);
        }
        return more;
    }

    /** The same on an order the caller locked and will save. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean holdReturnedGoods(Order order) {
        List<com.stockflow.order.internal.domain.DeliveryFailureCase> wanting = wantingGoodsHeld(order);
        if (wanting.isEmpty()) {
            return false;
        }
        Map<UUID, OrderLine> lines = linesById(order);
        Set<Sku> skus = returnedSkus(order, wanting);
        if (skus.isEmpty()) {
            return false;
        }
        inventory.prepareReservation(skus);
        boolean more = false;
        for (com.stockflow.order.internal.domain.DeliveryFailureCase failure : wanting) {
            String warehouseCode = warehouses.codeOf(failure.warehouseId()).orElse(null);
            if (warehouseCode == null) {
                continue;
            }
            List<com.stockflow.order.internal.domain.DeliveryFailureCases.Hold> taken =
                    deliveryFailures.holdsOf(failure.id());
            Map<UUID, Integer> held = heldUnits(taken);
            for (Map.Entry<UUID, Integer> wanted : failure.units().entrySet()) {
                OrderLine line = lines.get(wanted.getKey());
                if (line == null) {
                    continue;
                }
                int perCarton = unitsPerCarton(line);
                int cartons = failure.cartonsToHold(line.id(), perCarton, held.getOrDefault(line.id(), 0));
                if (cartons == 0) {
                    continue;
                }
                // One request per attempt: the case, the line, how many holds the case had for it (released ones stay
                // on record) and the warehouse - a retried attempt holds once, a later arrival holds again.
                long attempts = taken.stream().filter(hold -> hold.orderLineId().equals(line.id())).count();
                String attempt = "redelivery:%s:%s:%d:%s".formatted(failure.id(), line.id(), attempts, warehouseCode);
                HoldStockCommand command = new HoldStockCommand(UUID.nameUUIDFromBytes(
                        attempt.getBytes(StandardCharsets.UTF_8)), order.id().value(), line.sku(), warehouseCode,
                        cartons, perCarton);
                HoldStockResult result = inventory.holdAtWarehouse(command);
                if (!result.reservationIds().isEmpty()) {
                    order.holdMore(line.id(), result.reservationIds());
                    deliveryFailures.recordHolds(failure.id(), line.id(), result.reservationIds());
                    more = true;
                    log.info("Order {}: {} of {} carton(s) of {} that came back held again at {}", order.orderNumber(),
                            result.cartonsHeld(), command.cartons(), line.sku(), warehouseCode);
                }
            }
        }
        return more;
    }

    private List<com.stockflow.order.internal.domain.DeliveryFailureCase> wantingGoodsHeld(Order order) {
        return deliveryFailures.ofOrder(order.id().value()).stream()
                .filter(com.stockflow.order.internal.domain.DeliveryFailureCase::wantsGoodsHeld).toList();
    }

    /** The SKUs of the lines {@code wanting} cases hold goods for, sorted: the advisory locks to take. */
    private Set<Sku> returnedSkus(Order order,
                                  List<com.stockflow.order.internal.domain.DeliveryFailureCase> wanting) {
        Map<UUID, OrderLine> lines = linesById(order);
        Set<Sku> skus = new java.util.TreeSet<>(Comparator.comparing(Sku::code));
        wanting.forEach(failure -> failure.units().keySet().stream().map(lines::get)
                .filter(java.util.Objects::nonNull).forEach(line -> skus.add(line.sku())));
        return skus;
    }

    /**
     * WF-05 fix plan, section 2: a redelivery is released with every unit of its returned shipment held for its case -
     * the holds still HELD, which the release allocates.
     *
     * @return those holds
     * @throws BusinessException {@code ORDER_STOCK_NOT_HELD} naming the lines short: goods still in quarantine, or
     *         written off - Sales then cancel what was not delivered instead
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Set<UUID> heldForRedelivery(Order order, com.stockflow.order.internal.domain.DeliveryFailureCase failure) {
        List<com.stockflow.order.internal.domain.DeliveryFailureCases.Hold> taken =
                deliveryFailures.holdsOf(failure.id());
        Map<UUID, Integer> held = heldUnits(taken);
        Map<UUID, OrderLine> lines = linesById(order);
        List<String> short_ = failure.units().entrySet().stream()
                .filter(wanted -> held.getOrDefault(wanted.getKey(), 0) < wanted.getValue())
                .map(wanted -> "%s (%d of %d units)".formatted(lines.containsKey(wanted.getKey())
                        ? lines.get(wanted.getKey()).sku().code() : wanted.getKey(),
                        held.getOrDefault(wanted.getKey(), 0), wanted.getValue()))
                .toList();
        if (!short_.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("The goods of return receipt %s are not all "
                    + "held for order %s again: %s. They are held once released from quarantine and put away")
                    .formatted(failure.receiptNumber(), order.orderNumber(), String.join(", ", short_)));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        taken.forEach(hold -> ids.add(hold.reservationId()));
        Set<UUID> live = new LinkedHashSet<>();
        inventory.holds(ids).stream().filter(hold -> hold.status() == HoldView.Status.HELD)
                .forEach(hold -> live.add(hold.reservationId()));
        return live;
    }

    /**
     * Whether a pick took any of a delivery failure case's goods: one of its holds - allocations, once released - is
     * CONSUMED. Read without the stock rows' locks: a pick confirmed after this answer is put back
     * ({@code RedeliveryWithdrawn}), never lost.
     */
    public boolean pickedFor(com.stockflow.order.internal.domain.DeliveryFailureCase failure) {
        Set<UUID> ids = new LinkedHashSet<>();
        deliveryFailures.holdsOf(failure.id()).forEach(hold -> ids.add(hold.reservationId()));
        return !ids.isEmpty() && inventory.holds(ids).stream()
                .anyMatch(hold -> hold.status() == HoldView.Status.CONSUMED);
    }

    /** How many units of each line a delivery failure case has held: its holds still HELD (17 §4.2 step 3). */
    public Map<UUID, Integer> heldUnitsOf(com.stockflow.order.internal.domain.DeliveryFailureCase failure) {
        return heldUnits(deliveryFailures.holdsOf(failure.id()));
    }

    private Map<UUID, Integer> heldUnits(List<com.stockflow.order.internal.domain.DeliveryFailureCases.Hold> taken) {
        Map<UUID, Integer> held = new HashMap<>();
        if (taken.isEmpty()) {
            return held;
        }
        Map<UUID, UUID> lineOf = new HashMap<>();
        taken.forEach(hold -> lineOf.put(hold.reservationId(), hold.orderLineId()));
        for (HoldView hold : inventory.holds(lineOf.keySet())) {
            if (hold.status() == HoldView.Status.HELD) {
                held.merge(lineOf.get(hold.reservationId()), hold.quantity(), Integer::sum);
            }
        }
        return held;
    }

    /** A line's pieces per carton; a line not ordered in cartons counts one piece a carton, as a batch's units do. */
    private static int unitsPerCarton(OrderLine line) {
        return line.unitsPerCarton() == null || line.unitsPerCarton() <= 0 ? 1 : line.unitsPerCarton();
    }

    /**
     * 4B-8: the statuses in which an order still holds for batches to come - before its first release (CONFIRMED,
     * AWAITING_STOCK), and while its released batches go out (17 §4.5: later batches are held while the order is
     * worked). {@code OrderRepository#waitingForStockAtShippingWarehouse} lists the same.
     */
    static final Set<OrderStatus> TOPPED_UP = java.util.EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK,
            OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING, OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED);

    /** Whether the order is short of {@code sku} (or of anything, for null) - read without a lock, to skip the rest. */
    private boolean shortOf(UUID orderId, String sku) {
        Order order = orders.findById(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return false;
        }
        List<DeliveryBatch> pending = unreleasedBatches(order);
        if (pending != null) {
            Map<UUID, Map<UUID, Integer>> held = heldByBatch(order);
            Map<UUID, OrderLine> lines = linesById(order);
            return pending.stream().anyMatch(batch -> batch.lines().stream()
                    .filter(l -> sku == null || lines.get(l.orderLineId()).sku().code().equals(sku))
                    .anyMatch(l -> heldCartons(held, batch.id(), lines.get(l.orderLineId())) < l.cartons()));
        }
        Map<UUID, Integer> held = heldUnitsByLine(order);
        return cartonLines(order).stream().filter(line -> sku == null || line.sku().code().equals(sku))
                .anyMatch(line -> line.cartons() > held.getOrDefault(line.id(), 0) / line.unitsPerCarton());
    }

    private boolean topUp(UUID orderId) {
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || !TOPPED_UP.contains(order.status()) || order.shippingWarehouseId() == null) {
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
        List<DeliveryBatch> pending = unreleasedBatches(order);
        if (pending != null) {
            return holdByBatch(order, warehouseCode, pending);
        }
        if (order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.AWAITING_STOCK) {
            // Without a schedule the order holds line by line, and only before its release: once released its holds are
            // allocated and picked (no longer HELD), and holding again would take stock it already has.
            return false;
        }
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
     * 4B-8 (17 §4.2 step 3, BR-29): the batches not released yet hold what they lack, the earliest first - one hold
     * request per batch and line, recorded as that batch's. The request id names the batch, the line, how many holds the
     * line had (released ones stay on it) and the warehouse: a retry holds once, a later arrival holds again.
     */
    private boolean holdByBatch(Order order, String warehouseCode, List<DeliveryBatch> pending) {
        Map<UUID, Map<UUID, Integer>> held = heldByBatch(order);
        Map<UUID, OrderLine> lines = linesById(order);
        Set<Sku> skus = new java.util.TreeSet<>(Comparator.comparing(Sku::code));
        pending.forEach(batch -> batch.lines().stream()
                .filter(l -> heldCartons(held, batch.id(), lines.get(l.orderLineId())) < l.cartons())
                .forEach(l -> skus.add(lines.get(l.orderLineId()).sku())));
        if (skus.isEmpty()) {
            return false;
        }
        inventory.prepareReservation(skus);
        boolean more = false;
        for (DeliveryBatch batch : pending) {
            for (DeliveryBatch.Line batchLine : batch.lines()) {
                OrderLine line = lines.get(batchLine.orderLineId());
                int missing = batchLine.cartons() - heldCartons(held, batch.id(), line);
                if (missing <= 0) {
                    continue;
                }
                UUID requestId = UUID.nameUUIDFromBytes(("hold:%s:%s:%d:%s".formatted(batch.id(), line.id(),
                        line.reservationIds().size(), warehouseCode)).getBytes(StandardCharsets.UTF_8));
                HoldStockResult result = inventory.holdAtWarehouse(new HoldStockCommand(requestId, order.id().value(),
                        line.sku(), warehouseCode, missing, line.unitsPerCarton()));
                if (!result.reservationIds().isEmpty()) {
                    order.holdMore(line.id(), result.reservationIds());
                    batchRecords.recordHolds(order.id().value(), batch.id(), line.id(), result.reservationIds());
                    more = true;
                }
            }
        }
        return more;
    }

    /**
     * WF4-COUNT-EDGE (a), kltn-docs 20 §4.2 step 8 ("khoản giữ được xử lý như thiếu hàng"): an approved count wrote a
     * carton of {@code sku} off at {@code warehouseCode}, and what orders hold there by SKU - before their release - may
     * no longer be covered by the cartons left. The holds are recomputed: the units no carton covers are given up
     * ({@link InventoryService#shrinkHold}), so the order is short again exactly as the stock check at its confirmation
     * leaves an order short (17 §4.2 steps 3 and 5): the line holds fewer cartons, the batch shows what it lacks, it is
     * listed among the short delivery batches the production coordinator draws a production order up from (§4.12 step
     * 47) and its delay alerts are recomputed. Its status does not change here: as at confirmation, an order goes
     * AWAITING_STOCK once a production order for its shortage is sent (BR-04, {@link Order#awaitStock}).
     *
     * <p>Within the order, the latest delivery batch gives its holds up first, the newest hold first: held cartons count
     * for the earliest batch first (17 §4.2 step 3, BR-29).</p>
     *
     * <p><b>One order only.</b> When the uncovered holds belong to more than one order, nothing is given up: kltn-docs
     * does not say which order loses its hold first, and that rule waits for the owner (WF4-COUNT-EDGE, reported). The
     * holds stay as they are, as before this change - the release that finds too few cartons is refused
     * ({@code INSUFFICIENT_STOCK}).</p>
     *
     * <p>Locks: the order's row, then the SKU's advisory lock - as choosing a warehouse and topping up take them - and
     * the figures are read again under both. Idempotent: a repeated event finds every hold covered and changes
     * nothing.</p>
     *
     * @return the units of holds given up
     */
    public int giveUpUncovered(String sku, String warehouseCode) {
        Sku code = new Sku(sku);
        com.stockflow.inventory.api.SkuHoldCover cover = inventory.skuHoldCover(code, warehouseCode);
        if (cover.uncoveredUnits() == 0) {
            return 0;
        }
        Set<UUID> holders = holdersOf(cover);
        if (holders.size() != 1) {
            log.warn("{} at {}: {} unit(s) held by SKU are no longer covered after a count's shortage, held by {} orders "
                            + "{}; which one gives its hold up is not decided (WF4-COUNT-EDGE), so the holds are kept",
                    sku, warehouseCode, cover.uncoveredUnits(), holders.size(), holders);
            return 0;
        }
        UUID orderId = holders.iterator().next();
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return 0;
        }
        inventory.prepareReservation(Set.of(code));
        cover = inventory.skuHoldCover(code, warehouseCode);
        if (cover.uncoveredUnits() == 0) {
            return 0;
        }
        if (!holdersOf(cover).equals(holders)) {
            log.warn("{} at {}: the holds changed hands meanwhile ({}); kept as they are", sku, warehouseCode,
                    holdersOf(cover));
            return 0;
        }
        Map<UUID, OrderLine> lineOfHold = new HashMap<>();
        order.lines().forEach(line -> line.reservationIds().forEach(id -> lineOfHold.put(id, line)));
        Map<UUID, DeliveryBatchRecords.Hold> recorded = new HashMap<>();
        batchRecords.holdsOf(orderId).forEach(h -> recorded.put(h.reservationId(), h));
        Map<UUID, Integer> seqOf = new HashMap<>();
        contracts.findLiveByOrderId(orderId).ifPresent(contract -> contract.schedule()
                .forEach(batch -> seqOf.put(batch.id(), batch.seq())));
        // The latest batch first (a hold of no batch counts as the latest), then the newest hold.
        Comparator<com.stockflow.inventory.api.SkuHoldCover.Hold> latestFirst = Comparator
                .comparing((com.stockflow.inventory.api.SkuHoldCover.Hold h) -> {
                    DeliveryBatchRecords.Hold of = recorded.get(h.reservationId());
                    return of == null ? Integer.MAX_VALUE : seqOf.getOrDefault(of.batchId(), Integer.MAX_VALUE);
                })
                .thenComparing(com.stockflow.inventory.api.SkuHoldCover.Hold::reservedAt)
                .thenComparing(com.stockflow.inventory.api.SkuHoldCover.Hold::reservationId)
                .reversed();
        int left = cover.uncoveredUnits();
        int givenUp = 0;
        for (com.stockflow.inventory.api.SkuHoldCover.Hold hold : cover.holds().stream().sorted(latestFirst).toList()) {
            if (left <= 0) {
                break;
            }
            OrderLine line = lineOfHold.get(hold.reservationId());
            if (line == null || line.unitsPerCarton() == null || line.unitsPerCarton() <= 0) {
                log.warn("Order {}: hold {} of {} is on no carton line of the order; left as it is", order.orderNumber(),
                        hold.reservationId(), sku);
                continue;
            }
            // Whole cartons (17 BR-21): the cut is rounded up to the line's carton.
            int cartons = (left + line.unitsPerCarton() - 1) / line.unitsPerCarton();
            int cut = Math.min(hold.quantity(), cartons * line.unitsPerCarton());
            java.util.Optional<UUID> kept = inventory.shrinkHold(hold.reservationId(), hold.quantity() - cut);
            if (kept.isPresent()) {
                order.holdMore(line.id(), List.of(kept.get()));
                DeliveryBatchRecords.Hold batch = recorded.get(hold.reservationId());
                if (batch != null) {
                    batchRecords.recordHolds(orderId, batch.batchId(), line.id(), List.of(kept.get()));
                }
            }
            left -= cut;
            givenUp += cut;
        }
        if (givenUp > 0) {
            orders.save(order);
            log.info("Order {}: {} unit(s) of {} held at {} given up after a count's shortage; the order is short of them "
                    + "again", order.orderNumber(), givenUp, sku, warehouseCode);
        }
        return givenUp;
    }

    private static Set<UUID> holdersOf(com.stockflow.inventory.api.SkuHoldCover cover) {
        Set<UUID> holders = new LinkedHashSet<>();
        cover.holds().forEach(h -> holders.add(h.orderId()));
        return holders;
    }

    /**
     * 4B-8 (17 BR-03): the batch released is held in full at the shipping warehouse - every carton of every line it
     * carries, counting only the holds taken for it.
     *
     * @throws BusinessException {@code ORDER_STOCK_NOT_HELD} naming the lines short
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireBatchHeld(Order order, DeliveryBatch batch) {
        Map<UUID, Map<UUID, Integer>> held = heldByBatch(order);
        Map<UUID, OrderLine> lines = linesById(order);
        List<String> short_ = batch.lines().stream()
                .filter(l -> heldCartons(held, batch.id(), lines.get(l.orderLineId())) < l.cartons())
                .map(l -> "%s (%d of %d cartons)".formatted(lines.get(l.orderLineId()).sku().code(),
                        heldCartons(held, batch.id(), lines.get(l.orderLineId())), l.cartons()))
                .toList();
        if (!short_.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Delivery batch %d of order %s is not all held "
                    + "at its shipping warehouse: %s").formatted(batch.seq(), order.orderNumber(),
                    String.join(", ", short_)));
        }
    }

    /**
     * 4B-8: the holds that stand for {@code batch} still HELD - a SKU reservation before release, its allocations after
     * - what the release allocates.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Set<UUID> heldFor(Order order, DeliveryBatch batch) {
        Set<UUID> ids = new LinkedHashSet<>();
        batchRecords.holdsOf(order.id().value()).stream().filter(h -> h.batchId().equals(batch.id()))
                .forEach(h -> ids.add(h.reservationId()));
        if (ids.isEmpty()) {
            return ids;
        }
        Set<UUID> live = new LinkedHashSet<>();
        inventory.holds(ids).stream().filter(h -> h.status() == HoldView.Status.HELD)
                .forEach(h -> live.add(h.reservationId()));
        return live;
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
     * earliest batch first, as {@link #coverage} counts them. The next batch is the earliest not released yet (4B-8).
     * Read under the caller's lock on the order, in its transaction.
     *
     * @return empty for an order with no live contract, or none with a delivery schedule
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public java.util.Optional<NextBatch> nextBatch(Order order) {
        List<DeliveryBatch> pending = unreleasedBatches(order);
        DeliveryBatch first = pending == null || pending.isEmpty() ? null : pending.getFirst();
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
        Map<UUID, Map<UUID, Integer>> byBatch = contract == null ? Map.of() : heldByBatch(order);
        if (contract != null && !byBatch.isEmpty()) {
            // 4B-8: each batch counts the holds taken for it - picked ones too, so a batch on its way stays covered.
            Map<UUID, OrderLine> byId = linesById(order);
            for (DeliveryBatch batch : contract.schedule().stream()
                    .sorted(Comparator.comparingInt(DeliveryBatch::seq)).toList()) {
                List<LineCoverage> batchLines = new ArrayList<>();
                for (DeliveryBatch.Line line : batch.lines()) {
                    int takes = Math.min(line.cartons(), heldCartons(byBatch, batch.id(), byId.get(line.orderLineId())));
                    batchLines.add(new LineCoverage(line.orderLineId(), skuOf.get(line.orderLineId()), line.cartons(),
                            takes, line.cartons() - takes));
                }
                DeliveryBatchRecords.Window window = batchRecords.windowInForce(order.id().value(), batch);
                batches.add(new BatchCoverage(batch.seq(), window.start(), window.end(),
                        batchLines.stream().allMatch(l -> l.shortCartons() == 0), batchLines));
            }
        } else if (contract != null) {
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
                DeliveryBatchRecords.Window window = batchRecords.windowInForce(order.id().value(), batch);
                batches.add(new BatchCoverage(batch.seq(), window.start(), window.end(),
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

    /**
     * 4B-8: the units each batch's holds account for, by line - HELD (held, or allocated and not picked yet) or CONSUMED
     * (picked): a batch is covered by what was taken for it, whatever became of it since.
     */
    private Map<UUID, Map<UUID, Integer>> heldByBatch(Order order) {
        List<DeliveryBatchRecords.Hold> recorded = batchRecords.holdsOf(order.id().value());
        Map<UUID, Map<UUID, Integer>> held = new HashMap<>();
        if (recorded.isEmpty()) {
            return held;
        }
        Map<UUID, DeliveryBatchRecords.Hold> byId = new HashMap<>();
        recorded.forEach(h -> byId.put(h.reservationId(), h));
        for (HoldView hold : inventory.holds(byId.keySet())) {
            if (hold.status() == HoldView.Status.HELD || hold.status() == HoldView.Status.CONSUMED) {
                DeliveryBatchRecords.Hold h = byId.get(hold.reservationId());
                held.computeIfAbsent(h.batchId(), k -> new HashMap<>())
                        .merge(h.orderLineId(), hold.quantity(), Integer::sum);
            }
        }
        return held;
    }

    private static int heldCartons(Map<UUID, Map<UUID, Integer>> held, UUID batchId, OrderLine line) {
        if (line == null || line.unitsPerCarton() == null || line.unitsPerCarton() <= 0) {
            return 0;
        }
        return held.getOrDefault(batchId, Map.of()).getOrDefault(line.id(), 0) / line.unitsPerCarton();
    }

    private static Map<UUID, OrderLine> linesById(Order order) {
        Map<UUID, OrderLine> lines = new HashMap<>();
        order.lines().forEach(line -> lines.put(line.id(), line));
        return lines;
    }

    /**
     * 4B-8: the batches of the order's signed schedule not released yet, in order; null for an order without a live
     * contract or one with no schedule - it is held line by line, as before.
     */
    private List<DeliveryBatch> unreleasedBatches(Order order) {
        SalesContract contract = contracts.findLiveByOrderId(order.id().value()).orElse(null);
        if (contract == null || contract.schedule().isEmpty()) {
            return null;
        }
        Set<UUID> released = new java.util.HashSet<>();
        batchRecords.releasesOf(order.id().value()).forEach(r -> released.add(r.batchId()));
        return contract.schedule().stream().filter(batch -> !released.contains(batch.id()))
                .sorted(Comparator.comparingInt(DeliveryBatch::seq)).toList();
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
