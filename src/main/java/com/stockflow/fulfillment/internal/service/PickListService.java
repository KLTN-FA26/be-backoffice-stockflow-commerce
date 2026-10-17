package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.PickLine;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickList;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineMapper;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.order.api.OrderReservations;
import com.stockflow.order.api.OrderService;
import com.stockflow.inventory.api.ReallocateStockCommand;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pick lines: planning them from the order's holds, confirming them by scan with an in-process stock
 * deduction, and putting the goods of a cancelled order back (SCRUM-267, 274, 275).
 *
 * <p><b>Lock order.</b> Every write locks the pick task first ({@code PickJpaRepository.lockById},
 * {@code SELECT … FOR UPDATE}) and only then reaches inventory, which locks the stock row. No path takes
 * them the other way round: cancelling an order locks stock rows in the order's transaction and touches
 * the pick only afterwards, in the listener's own transaction. Two confirmations of one line are
 * therefore serialised on the pick, and the second sees the line PICKED.</p>
 *
 * <p><b>One transaction.</b> The line turning PICKED, the hold turning CONSUMED, {@code on_hand}
 * falling, the {@code PICK} ledger row and {@code StockDeducted} commit together or not at all — which
 * is the reason consumption is in-process rather than an HTTP call (decision G4 §6 #7).</p>
 */
@Service
@Transactional
class PickListService implements PickLists {

    private static final Logger log = LoggerFactory.getLogger(PickListService.class);

    private final PickJpaRepository picks;
    private final PickLineJpaRepository pickLines;
    private final OrderService orders;
    private final InventoryService inventory;
    private final InventoryControlService itemControls;
    private final PickConsumption consumption;
    private final WarehouseService warehouses;
    private final Clock clock;
    private final OrderReservations orderReservations;

    /** The hold reason X-SHORT puts the order ON_HOLD with (docs 04c, 03 order machine). */
    static final String INVENTORY_ISSUE = "INVENTORY_ISSUE";

    PickListService(PickJpaRepository picks, PickLineJpaRepository pickLines, OrderService orders,
                    InventoryService inventory, InventoryControlService itemControls, PickConsumption consumption,
                    WarehouseService warehouses, Clock clock, OrderReservations orderReservations) {
        this.orderReservations = orderReservations;
        this.picks = picks;
        this.pickLines = pickLines;
        this.orders = orders;
        this.inventory = inventory;
        this.itemControls = itemControls;
        this.consumption = consumption;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ called by the task service

    /**
     * Plans the lines of a task that has none: at creation, and lazily at picking-start for a task opened
     * before lines existed. Idempotent; the caller holds the pick's lock (or has just created it).
     */
    public void planLines(PickJpaEntity pick, OrderSummary order) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        if (!stored.isEmpty()) {
            return;
        }
        PickList list = toList(pick, stored);
        List<PickLine> added = list.plan(orderLinesOf(order), holdsOf(order), Identifiers::newId);
        if (!added.isEmpty()) {
            pickLines.saveAll(added.stream().map(line -> PickLineMapper.toNewEntity(pick.getId(), line)).toList());
            log.info("Planned {} pick line(s) for task {} of order {}", added.size(), pick.getId(),
                    pick.getOrderNumber());
        }
    }

    /**
     * Docs 07 step 9: the task closes only when every line is picked. A task with no lines may close only
     * when the order has nothing live to pick in it — otherwise its lines are planned by starting it
     * again, and picked.
     */
    public void requireAllPicked(PickJpaEntity pick, OrderSummary order) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        if (stored.isEmpty()) {
            boolean somethingToPick = holdsOf(order).values().stream().anyMatch(PickList.Hold::live);
            if (somethingToPick) {
                throw new BusinessException(ErrorCode.PICK_LINES_INCOMPLETE,
                        "The task has no pick lines yet; start picking to load them");
            }
            return;
        }
        toList(pick, stored).requireAllPicked();
    }

    /** The order was cancelled: open lines are cancelled with the task (their holds were released). */
    public void cancelOpenLines(PickJpaEntity pick) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        Set<UUID> cancelled = list.cancelOpenLines().stream().map(PickLine::id).collect(Collectors.toSet());
        writeBack(list, stored, cancelled);
    }

    // ------------------------------------------------------------------ PickLists

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PickLineView> lines(UUID taskId, int page, int size, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        TaskAccess.requireVisible(pick, actor);
        Page<PickLineJpaEntity> found = pickLines.findByPickId(taskId,
                Pages.of(page, size, Sort.by(Sort.Direction.ASC, "sequenceNo", "id")));
        Map<String, String> barcodes = barcodesOf(found.getContent().stream().map(PickLineJpaEntity::getSku).toList());
        return Pages.toResponse(found, row -> view(taskId, PickLineMapper.toDomain(row), barcodes.get(row.getSku())));
    }

    @Override
    public PickLineView confirm(UUID taskId, UUID lineId, String locationCode, String scannedCode, int quantity,
                                CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        TaskAccess.orderInFulfilment(orders, pick);
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        String barcode = list.line(lineId).map(line -> barcodesOf(List.of(line.sku())).get(line.sku())).orElse(null);
        Instant now = clock.instant();

        PickList.Confirmation outcome = list.confirm(lineId, locationCode, scannedCode, quantity, barcode,
                actor.userId(), now);
        PickLine line = outcome.line();
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        if (line.reservationId() != null) {
            // The pick is locked; inventory now locks the stock row (pick -> stock_item, never the reverse).
            consumption.consumeForPick(line.reservationId(), line.id(), actor.userId());
            line.recordConsumed(now);
        }
        writeBack(list, stored, Set.of(line.id()));
        log.info("Pick line {} of task {} picked: {} x {} from {}", line.id(), taskId, line.pickedQty(), line.sku(),
                line.locationCode());
        return view(taskId, line, barcode);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PutBackRow> putBacks(UUID warehouseId, int page, int size, CurrentUser actor) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        Optional<Set<UUID>> allowed = WarehouseScope.restriction().map(scope -> scope.ids());
        Specification<PickLineJpaEntity> waiting = (root, query, cb) -> {
            var task = query.subquery(UUID.class);
            var pick = task.from(PickJpaEntity.class);
            List<Predicate> where = new ArrayList<>(List.of(
                    cb.equal(pick.get("id"), root.get("pickId")),
                    cb.equal(pick.get("status"), PickStatus.CANCELLED),
                    cb.isTrue(pick.get("needsPutBack"))));
            if (warehouseId != null) {
                where.add(cb.equal(pick.get("warehouseId"), warehouseId));
            }
            allowed.ifPresent(ids -> where.add(ids.isEmpty() ? cb.disjunction() : pick.get("warehouseId").in(ids)));
            task.select(pick.get("id")).where(where.toArray(Predicate[]::new));
            return cb.and(cb.equal(root.get("status"), PickLineStatus.PICKED), cb.exists(task));
        };
        Page<PickLineJpaEntity> found = pickLines.findAll(waiting,
                Pages.of(page, size, Sort.by(Sort.Direction.ASC, "pickedAt", "id")));
        Map<UUID, PickJpaEntity> tasks = picks.findAllById(found.getContent().stream()
                        .map(PickLineJpaEntity::getPickId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PickJpaEntity::getId, Function.identity()));
        return Pages.toResponse(found, row -> {
            PickJpaEntity task = tasks.get(row.getPickId());
            return new PutBackRow(row.getPickId(), task.getOrderNumber(), task.getWarehouseId(), row.getId(),
                    row.getSku(), row.getLotNumber(), row.getPickedQty(), row.getLocationCode(), row.getPickedAt());
        });
    }

    @Override
    public PickLineView putBack(UUID taskId, UUID lineId, String locationCode, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        PickList.PutBackTarget target = warehouses.findLocation(locationCode.trim())
                .map(view -> new PickList.PutBackTarget(view.locationCode(), view.warehouseId(),
                        view.kind() == StorageLocationView.Kind.BIN, view.usable()))
                .orElse(null);

        PickList.PutBack outcome = list.putBack(lineId, target, actor.userId(), clock.instant());
        PickLine line = outcome.line();
        String barcode = barcodesOf(List.of(line.sku())).get(line.sku());
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        if (line.reservationId() == null) {
            // Only a re-allocation (SCRUM-268) makes a line without a hold, and that flow does not exist yet.
            throw new BusinessException(ErrorCode.PUT_BACK_NOT_REQUIRED, "The line consumed no hold");
        }
        consumption.putBack(new PutBackCommand(line.id(), line.reservationId(), line.sku(), line.lotNumber(),
                line.putBackLocationCode(), line.putBackQty(), actor.userId()));
        writeBack(list, stored, Set.of(line.id()));
        log.info("Pick line {} of task {} put back: {} x {} onto {}", line.id(), taskId, line.putBackQty(),
                line.sku(), line.putBackLocationCode());
        return view(taskId, line, barcode);
    }

    @Override
    public PickLineView reportShort(UUID taskId, UUID lineId, String locationCode, int found, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        TaskAccess.orderInFulfilment(orders, pick);
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        PickList.Confirmation outcome = list.reportShort(lineId, locationCode, found);
        PickLine line = outcome.line();
        String barcode = barcodesOf(List.of(line.sku())).get(line.sku());
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        writeBack(list, stored, Set.of(line.id()));
        // X-SHORT step 02: the order stops; it does not cancel, and no stock is deducted (docs 04c).
        orders.putOnDesignHold(pick.getOrderId(), INVENTORY_ISSUE);
        log.info("Pick line {} of task {} short: {} of {} x {} missing at {}", line.id(), taskId, line.shortQty(),
                line.allocatedQty(), line.sku(), line.locationCode());
        return view(taskId, line, barcode);
    }

    @Override
    public List<PickLineView> reallocateShort(UUID taskId, UUID lineId, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        TaskAccess.orderInFulfilment(orders, pick);
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        PickLine shortLine = list.line(lineId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "Task %s has no pick line %s".formatted(taskId, lineId)));
        if (shortLine.status() != PickLineStatus.SHORT || shortLine.reservationId() == null) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN,
                    "Only a SHORT line with an allocation is reallocated; it is " + shortLine.status());
        }
        // Pick first, then stock rows (class javadoc): the pick is locked, inventory locks the SKU and its rows.
        List<StockReservation> allocated = inventory.reallocate(new ReallocateStockCommand(pick.getOrderId(),
                shortLine.reservationId(), UUID.nameUUIDFromBytes(("reallocate:" + lineId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        orderReservations.replaceHold(pick.getOrderId(), shortLine.reservationId(),
                allocated.stream().map(StockReservation::reservationId).toList());
        Map<String, UUID> warehouseOf = new HashMap<>();
        List<PickList.Hold> holds = allocated.stream().map(hold -> new PickList.Hold(hold.reservationId(),
                shortLine.sku(), hold.locationCode(), warehouseOf.computeIfAbsent(hold.locationCode(),
                        code -> warehouses.findLocation(code).map(StorageLocationView::warehouseId).orElse(null)),
                hold.lotNumber(), hold.quantity(), true)).toList();
        List<PickLine> added = list.replaceShort(lineId, holds, Identifiers::newId);
        writeBack(list, stored, Set.of(lineId));
        pickLines.saveAll(added.stream().map(line -> PickLineMapper.toNewEntity(pick.getId(), line)).toList());
        if (!list.hasShortLines()) {
            orders.resolveDesignHold(pick.getOrderId(), actor.userId(),
                    "Short pick reallocated: line %s".formatted(lineId));
        }
        log.info("Short line {} of task {} reallocated to {} new line(s)", lineId, taskId, added.size());
        Map<String, String> barcodes = barcodesOf(List.of(shortLine.sku()));
        return added.stream().map(line -> view(taskId, line, barcodes.get(line.sku()))).toList();
    }

    // ------------------------------------------------------------------ helpers

    private PickJpaEntity lockPick(UUID taskId) {
        return picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static PickList toList(PickJpaEntity pick, List<PickLineJpaEntity> stored) {
        return new PickList(pick.getId(), pick.getWarehouseId(), pick.getStatus(), pick.isNeedsPutBack(),
                stored.stream().map(PickLineMapper::toDomain).toList());
    }

    /** Copies the lines the aggregate changed back onto their rows; dirty checking writes them at commit. */
    private void writeBack(PickList list, List<PickLineJpaEntity> stored, Set<UUID> changed) {
        Map<UUID, PickLineJpaEntity> rows = stored.stream()
                .collect(Collectors.toMap(PickLineJpaEntity::getId, Function.identity()));
        for (PickLine line : list.lines()) {
            if (changed.contains(line.id())) {
                PickLineJpaEntity row = rows.get(line.id());
                PickLineMapper.applyTo(line, row);
                pickLines.save(row);
            }
        }
    }

    private static List<PickList.OrderLine> orderLinesOf(OrderSummary order) {
        return order.lines().stream()
                .map(line -> new PickList.OrderLine(line.lineId(), line.sku(), line.designSnapshotId() != null,
                        line.reservationIds()))
                .toList();
    }

    /** The holds of the order's stock lines, with the warehouse each one's location belongs to. */
    private Map<UUID, PickList.Hold> holdsOf(OrderSummary order) {
        Set<UUID> ids = order.lines().stream()
                .filter(line -> line.designSnapshotId() == null)
                .flatMap(line -> line.reservationIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> warehouseOf = new HashMap<>();
        Map<UUID, PickList.Hold> holds = new HashMap<>();
        for (HoldView hold : inventory.holds(ids)) {
            UUID where = warehouseOf.computeIfAbsent(hold.locationCode(), code -> warehouses.findLocation(code)
                    .map(StorageLocationView::warehouseId).orElse(null));
            holds.put(hold.reservationId(), new PickList.Hold(hold.reservationId(), hold.sku(), hold.locationCode(),
                    where, hold.lotNumber(), hold.quantity(), hold.status() == HoldView.Status.HELD));
        }
        return holds;
    }

    /** The barcode of each SKU; a SKU without one maps to nothing. One lookup per distinct SKU. */
    private Map<String, String> barcodesOf(Collection<String> skus) {
        Map<String, String> barcodes = new HashMap<>();
        for (String sku : new LinkedHashSet<>(skus)) {
            String barcode = itemControls.logistics(new Sku(sku)).logistics().barcode();
            if (barcode != null) {
                barcodes.put(sku, barcode);
            }
        }
        return barcodes;
    }

    private static PickLineView view(UUID taskId, PickLine line, String barcode) {
        return new PickLineView(line.id(), taskId, line.sequenceNo(), line.orderLineId(), line.sku(), barcode,
                line.locationCode(), line.lotNumber(), line.allocatedQty(), line.pickedQty(), line.status().name(),
                line.pickedAt(), line.putBackQty(), line.putBackLocationCode(), line.putBackAt(), line.shortQty());
    }
}
