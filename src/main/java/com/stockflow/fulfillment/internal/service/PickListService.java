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
import com.stockflow.fulfillment.internal.domain.ShortPickReason;
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
import com.stockflow.inventory.consumption.StageCommand;
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
 * Pick lines, one carton each: planning them from the order's holds, confirming them by scanning the carton's LPN with
 * an in-process stock deduction, reporting a carton short, and putting the carton of a cancelled order back whole
 * (SCRUM-267, 274, 275; team plan 4C-4).
 *
 * <p><b>Lock order.</b> Every write locks the pick task first ({@code PickJpaRepository.lockById},
 * {@code SELECT … FOR UPDATE}) and only then reaches inventory, which locks the stock row. No path takes
 * them the other way round: cancelling an order locks stock rows in the order's transaction and touches
 * the pick only afterwards, in the listener's own transaction. Two confirmations of one line are
 * therefore serialised on the pick, and the second sees the line PICKED. A write that also changes the order
 * (a short report, a reallocation) locks it after the pick and before any stock row, because a cancellation
 * locks the order first and the stock rows second.</p>
 *
 * <p><b>One transaction.</b> The line turning PICKED, the hold turning CONSUMED, {@code on_hand}
 * falling, the {@code PICK} ledger row and {@code StockDeducted} commit together or not at all — which
 * is the reason consumption is in-process rather than an HTTP call (decision G4 §6 #7). The same holds for a
 * STAGING task (team plan 4C-5): the line turning STAGED and its carton moving to the staging area commit together,
 * and so do the lines turning PICKED and their cartons being deducted when the task moves to dispatch.</p>
 */
@Service
@Transactional
class PickListService implements PickLists {

    private static final Logger log = LoggerFactory.getLogger(PickListService.class);

    /**
     * Why a carton left the order and its line still lacks it, to be replaced as a short pick is reallocated (07 §4.4):
     * damaged at the check station (08 §4.2 step 4), or taken out of a shipment the warehouse manager cancelled before
     * dispatch (09 §5.1; RTM-RTDCANCEL, 2026-11-19). A carton removed because the rest of the order was closed is no
     * longer wanted, and one removed with its order cancelled has no order to go to.
     */
    private static final Set<com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> REPLACEABLE =
            java.util.EnumSet.of(com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.DAMAGED,
                    com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.SHIPMENT_CANCELLED);

    /**
     * Why a carton removed at the check station goes back onto a bin, not into quarantine, while its task goes on: the
     * rest of its order was closed (WF4-CLOSE-REST), or its shipment was cancelled before dispatch and it is not
     * damaged (RTM-RTDCANCEL).
     */
    private static final Set<com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> BACK_TO_A_BIN =
            java.util.EnumSet.of(com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.REMAINDER_CLOSED,
                    com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.SHIPMENT_CANCELLED);

    private final PickJpaRepository picks;
    private final PickLineJpaRepository pickLines;
    private final OrderService orders;
    private final InventoryService inventory;
    private final InventoryControlService itemControls;
    private final PickConsumption consumption;
    private final WarehouseService warehouses;
    private final Clock clock;
    private final OrderReservations orderReservations;
    private final com.stockflow.fulfillment.internal.repository.PackJpaRepository packs;
    /** 4C-6: the check station's shipping cartons, to know a line whose carton was removed as damaged. */
    private final com.stockflow.fulfillment.internal.repository.PackageJpaRepository packages;
    /** RTM-RTDCANCEL: whether a task's check was reopened by a shipment cancelled before dispatch. */
    private final com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository shipments;
    /** 07 §4.3 step 7: the top-up of a task opened before its order's release allocates what was held since. */
    private final com.stockflow.order.api.PrePicking prePicking;

    PickListService(PickJpaRepository picks, PickLineJpaRepository pickLines, OrderService orders,
                    InventoryService inventory, InventoryControlService itemControls, PickConsumption consumption,
                    WarehouseService warehouses, Clock clock, OrderReservations orderReservations,
                    com.stockflow.fulfillment.internal.repository.PackJpaRepository packs,
                    com.stockflow.fulfillment.internal.repository.PackageJpaRepository packages,
                    com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository shipments,
                    com.stockflow.order.api.PrePicking prePicking) {
        this.shipments = shipments;
        this.prePicking = prePicking;
        this.packs = packs;
        this.packages = packages;
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
     * Plans a line for every live hold of the order that has none: at creation, lazily at picking-start for a task
     * opened before lines existed, and for a top-up (07 §4.3 step 7) - a STAGED or PICKED task given lines goes back
     * to PICKING. Idempotent; the caller holds the pick's lock (or has just created it).
     *
     * @return the lines added
     */
    public List<PickLine> planLines(PickJpaEntity pick, OrderSummary order) {
        return planLines(pick, order, pick.getAssignedUserId());
    }

    /**
     * {@link #planLines(PickJpaEntity, OrderSummary)}, naming who planned them. WF4-COUNT-EDGE (b): a hold an approved
     * count gave up with its carton before any line stood on it (the order released, no task yet) gets its line SHORT
     * at once ({@link PickList#plan(List, Map, Set, java.util.function.Supplier)}), and the order is held for stock
     * ({@code INVENTORY}) in {@code by}'s name, as a picker's short report holds it (07 §4.4 step 9) - for the warehouse
     * manager to reallocate the line ({@link #reallocateShort}), which resolves the hold once no line is short. The
     * caller holds the pick's lock and, when it took it, the order's after it.
     */
    public List<PickLine> planLines(PickJpaEntity pick, OrderSummary order, UUID by) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        Map<UUID, PickList.Hold> holds = holdsOf(pick, order);
        Set<UUID> released = holds.values().stream().filter(hold -> !hold.live()).map(PickList.Hold::reservationId)
                .collect(Collectors.toSet());
        Set<UUID> givenUp = released.isEmpty() ? Set.of() : inventory.holdsGivenUpWithTheirCarton(released);
        List<PickLine> added = list.plan(orderLinesOf(order), holds, givenUp, Identifiers::newId);
        if (!added.isEmpty()) {
            pickLines.saveAll(added.stream().map(line -> PickLineMapper.toNewEntity(pick.getId(), line)).toList());
            if (pick.getStatus() == PickStatus.STAGED || pick.getStatus() == PickStatus.PICKED) {
                pick.reopen();
                picks.save(pick);
            }
            log.info("Planned {} pick line(s) for task {} of order {}{}", added.size(), pick.getId(),
                    pick.getOrderNumber(), stored.isEmpty() ? "" : " (top-up)");
            List<PickLine> shortages = added.stream().filter(line -> line.status() == PickLineStatus.SHORT).toList();
            if (!shortages.isEmpty()) {
                PickLine first = shortages.getFirst();
                holdForInventoryIfHoldable(pick.getOrderId(), by, ("Carton %s (%d x %s) counted missing at %s before "
                        + "the task: %d line(s) short").formatted(first.lpn(), first.allocatedQty(), first.sku(),
                        PickList.expectedAt(first), shortages.size()));
                log.info("Task {} of order {}: {} line(s) planned SHORT, their cartons written off by an approved count",
                        pick.getId(), pick.getOrderNumber(), shortages.size());
            }
        }
        return added;
    }

    /**
     * The STAGED task moves to dispatch (07 §4.3 step 8): every STAGED line turns PICKED and its carton is deducted
     * where it stands. The caller holds the pick's lock and, after it, the order's; inventory locks the SKUs, the
     * cartons and the rows - pick, order, stock rows, as everywhere. The task itself moves after this.
     */
    void moveStagedLinesToDispatch(PickJpaEntity pick, UUID actorId) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        Instant now = clock.instant();
        List<PickLine> moved = list.moveToDispatch();
        // One call for the whole task (review M1): inventory takes every SKU lock sorted, then the cartons, then the
        // rows. A line without a hold fails loudly (4C-4 requireHold): skipping it would mark it PICKED undeducted.
        consumption.consumeStaged(moved.stream()
                .map(line -> new com.stockflow.inventory.consumption.StagedLine(requireHold(line, "moved to dispatch"),
                        line.id()))
                .toList(), actorId);
        moved.forEach(line -> line.recordConsumed(now));
        writeBack(list, stored, moved.stream().map(PickLine::id).collect(Collectors.toSet()));
        log.info("Task {} of order {} moved to dispatch: {} staged carton(s) deducted", pick.getId(),
                pick.getOrderNumber(), moved.size());
    }

    /**
     * Docs 07 step 9: the task closes only when every line is picked. A task with no lines may close only
     * when the order has nothing live to pick in it — otherwise its lines are planned by starting it
     * again, and picked.
     */
    public void requireAllPicked(PickJpaEntity pick, OrderSummary order) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        if (stored.isEmpty()) {
            boolean somethingToPick = holdsOf(pick, order).values().stream().anyMatch(PickList.Hold::live);
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
        Map<UUID, com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> removed =
                removalsOf(found.getContent().stream().map(PickLineJpaEntity::getId).toList());
        Set<UUID> replaceable = replaceableRemovals(pick, removed);
        return Pages.toResponse(found, row -> {
            PickLineView line = view(taskId, PickLineMapper.toDomain(row), barcodes.get(row.getSku()));
            var reason = removed.get(row.getId());
            return reason == null ? line : line.withRemoval(reason.name(), replaceable.contains(row.getId()));
        });
    }

    /** The reason the check station removed each line's carton, for the lines among {@code lineIds} that were. */
    private Map<UUID, com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> removalsOf(
            Collection<UUID> lineIds) {
        Map<UUID, com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> reasons = new HashMap<>();
        if (!lineIds.isEmpty()) {
            packages.findByPickLineIdIn(lineIds).stream()
                    .filter(carton -> carton.getStatus() == com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED
                            && carton.getRemovalReason() != null)
                    .forEach(carton -> reasons.put(carton.getPickLineId(), carton.getRemovalReason()));
        }
        return reasons;
    }

    /**
     * Of the lines whose carton was removed, the ones the reallocation would replace now - removed as damaged, or out of
     * a shipment cancelled before dispatch ({@link #REPLACEABLE}) - by the very rule of {@link #replaceRemoved}: the
     * task still takes more lines ({@link #requireOpenForMore}) and its order line lacks the carton. Empty when nothing
     * replaceable was removed, which costs no order read.
     */
    private Set<UUID> replaceableRemovals(PickJpaEntity pick,
            Map<UUID, com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> removed) {
        Set<UUID> damaged = removed.entrySet().stream()
                .filter(e -> REPLACEABLE.contains(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (damaged.isEmpty() || !isOpenForMore(pick)) {
            return Set.of();
        }
        OrderSummary order = orders.findById(pick.getOrderId()).orElse(null);
        if (order == null) {
            return Set.of();
        }
        PickList list = toList(pick, pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId()));
        Map<UUID, Integer> lacking = lacking(pick, order, list);
        // A line replaced already is not offered again, though other removed cartons of its order line still lack theirs
        // (a shipment of several cartons cancelled before dispatch, RTM-RTDCANCEL).
        Set<UUID> replaced = pickLines.findByReplacesLineIdIn(damaged).stream()
                .map(PickLineJpaEntity::getReplacesLineId).collect(Collectors.toSet());
        return list.lines().stream().filter(line -> damaged.contains(line.id()) && !replaced.contains(line.id())
                && lacking.getOrDefault(line.orderLineId(), 0) >= line.allocatedQty()).map(PickLine::id)
                .collect(Collectors.toSet());
    }

    @Override
    public PickLineView confirm(UUID taskId, UUID lineId, String locationCode, String lpn, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        TaskAccess.requireWorker(pick, actor);
        TaskAccess.orderInFulfilment(orders, pick);
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        Instant now = clock.instant();

        PickList.Confirmation outcome = list.confirm(lineId, locationCode, lpn, actor.userId(), now);
        PickLine line = outcome.line();
        String barcode = barcodesOf(List.of(line.sku())).get(line.sku());
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        if (line.status() == PickLineStatus.STAGED) {
            // A STAGING task (4C-5): the carton moves to the staging area, still held - pick, then SKU, carton, row.
            consumption.stage(new StageCommand(line.id(), requireHold(line, "staged"), line.lpn(),
                    line.stagedLocationCode(), actor.userId()));
        } else {
            // The pick is locked; inventory now locks the stock row (pick -> stock_item, never the reverse).
            consumption.consumeForPick(requireHold(line, "confirmed"), line.id(), actor.userId());
            line.recordConsumed(now);
        }
        writeBack(list, stored, Set.of(line.id()));
        log.info("Pick line {} of task {} {}: carton {} ({} x {}) from {}", line.id(), taskId,
                line.status() == PickLineStatus.STAGED ? "staged at " + line.stagedLocationCode() : "picked",
                line.lpn(), line.pickedQty(), line.sku(), line.locationCode());
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
            // 4C-6: a carton the check station removed as damaged waits too, cancelled task or not, for quarantine; and
            // one removed because the rest of its order was closed (WF4-CLOSE-REST), for a bin.
            var live = query.subquery(UUID.class);
            var livePick = live.from(PickJpaEntity.class);
            List<Predicate> liveWhere = new ArrayList<>(List.of(cb.equal(livePick.get("id"), root.get("pickId"))));
            if (warehouseId != null) {
                liveWhere.add(cb.equal(livePick.get("warehouseId"), warehouseId));
            }
            allowed.ifPresent(ids -> liveWhere.add(ids.isEmpty() ? cb.disjunction()
                    : livePick.get("warehouseId").in(ids)));
            live.select(livePick.get("id")).where(liveWhere.toArray(Predicate[]::new));
            // 07 §4.5 step 13: the task cancelled earliest first, its cartons by when they were picked. A carton the
            // check station removed from a task that was not cancelled has no cancellation to wait since: after them.
            if (!Long.class.equals(query.getResultType())) {
                var cancelled = query.subquery(java.time.Instant.class);
                var owner = cancelled.from(PickJpaEntity.class);
                cancelled.select(owner.<java.time.Instant>get("cancelledAt"))
                        .where(cb.equal(owner.get("id"), root.get("pickId")));
                query.orderBy(cb.asc(cancelled), cb.asc(root.get("pickedAt")), cb.asc(root.get("id")));
            }
            return cb.and(cb.gt(root.<Integer>get("pickedQty"), 0), cb.or(
                    cb.and(root.get("status").in(PickLineStatus.PICKED, PickLineStatus.STAGED), cb.exists(task)),
                    cb.and(cb.equal(root.get("status"), PickLineStatus.PICKED), cb.exists(removedToPutBack(root, query,
                            cb)), cb.exists(live))));
        };
        // Unsorted on purpose: the specification orders the page (a sort here would replace its ordering).
        Page<PickLineJpaEntity> found = pickLines.findAll(waiting, Pages.of(page, size));
        Map<UUID, PickJpaEntity> tasks = picks.findAllById(found.getContent().stream()
                        .map(PickLineJpaEntity::getPickId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PickJpaEntity::getId, Function.identity()));
        Set<UUID> damaged = removedDamaged(found.getContent().stream().map(PickLineJpaEntity::getId).toList());
        // 07 BR-12: a carton no bin of its warehouse takes may go into OVERFLOW; a damaged one goes to quarantine.
        Map<UUID, Map<String, List<String>>> overflow = new HashMap<>();
        found.getContent().stream().filter(row -> row.getLpn() != null && !damaged.contains(row.getId()))
                .collect(Collectors.groupingBy(row -> tasks.get(row.getPickId()).getWarehouseId(),
                        Collectors.mapping(PickLineJpaEntity::getLpn, Collectors.toSet())))
                .forEach((warehouse, lpns) -> overflow.put(warehouse, consumption.overflowAreasFor(warehouse, lpns)));
        return Pages.toResponse(found, row -> {
            PickJpaEntity task = tasks.get(row.getPickId());
            // A staged carton stands in the staging area, not at the bin it was picked from (4C-5).
            return new PutBackRow(row.getPickId(), task.getOrderNumber(), task.getWarehouseId(), row.getId(),
                    row.getSku(), row.getLotNumber(), row.getLpn(), row.getPickedQty(),
                    row.getStatus() == PickLineStatus.STAGED ? row.getStagedLocationCode() : row.getLocationCode(),
                    row.getPickedAt(), damaged.contains(row.getId()),
                    overflow.getOrDefault(task.getWarehouseId(), Map.of()).get(row.getLpn()));
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks the pick, then inventory takes the SKU, the carton and its row (07 §4.5 step 14). The carton goes back
     * CONSUMED to IN_STOCK, sealed, at the bin (4C-2's transition), keeping its LPN and its count. A staged line's
     * carton was never deducted: it is moved from the staging area to the bin instead (4C-5).</p>
     */
    @Override
    public PickLineView putBack(UUID taskId, UUID lineId, String locationCode, String lpn, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        PickList.PutBackTarget target = warehouses.findLocation(locationCode.trim())
                .map(view -> new PickList.PutBackTarget(view.locationCode(), view.warehouseId(),
                        view.kind() == StorageLocationView.Kind.BIN, view.usable(),
                        view.areaType() == StorageLocationView.AreaType.QUARANTINE,
                        view.areaType() == StorageLocationView.AreaType.OVERFLOW))
                .orElse(null);

        boolean damaged = removedDamaged(List.of(lineId)).contains(lineId);
        boolean toBin = removedFor(List.of(lineId), BACK_TO_A_BIN).contains(lineId);
        PickList.PutBack outcome = list.putBack(lineId, lpn, target, damaged, toBin, actor.userId(),
                clock.instant());
        PickLine line = outcome.line();
        String barcode = barcodesOf(List.of(line.sku())).get(line.sku());
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        // A line put back names its carton (PickList#putBack), so it has the hold that took it (tg_pick_line_carton).
        // A staged line's carton was never deducted: it is moved back from the staging area instead (4C-5).
        PutBackCommand command = new PutBackCommand(line.id(), requireHold(line, "put back"), line.lpn(),
                line.putBackLocationCode(), actor.userId());
        if (outcome.fromStaging()) {
            consumption.unstage(command);
        } else if (outcome.toQuarantine()) {
            consumption.putBackToQuarantine(command);
        } else {
            consumption.putBack(command);
        }
        writeBack(list, stored, Set.of(line.id()));
        log.info("Pick line {} of task {} put back: carton {} ({} x {}) onto {}", line.id(), taskId, line.lpn(),
                line.putBackQty(), line.sku(), line.putBackLocationCode());
        return view(taskId, line, barcode);
    }

    /**
     * The lines among {@code lineIds} whose carton the check station removed as damaged (4C-6): they go into quarantine
     * through the put-back queue (08 §4.2 step 4).
     */
    private Set<UUID> removedDamaged(Collection<UUID> lineIds) {
        return removedFor(lineIds, Set.of(com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.DAMAGED));
    }

    /**
     * The lines among {@code lineIds} whose carton left the order and is still lacking ({@link #REPLACEABLE}): removed
     * as damaged, or out of a shipment cancelled before dispatch.
     */
    private Set<UUID> removedReplaceable(Collection<UUID> lineIds) {
        return removedFor(lineIds, REPLACEABLE);
    }

    /** The lines among {@code lineIds} whose carton the check station removed for one of {@code reasons}. */
    private Set<UUID> removedFor(Collection<UUID> lineIds,
                                 Set<com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason> reasons) {
        return lineIds.isEmpty() ? Set.of() : packages.findByPickLineIdIn(lineIds).stream()
                .filter(carton -> carton.getStatus() == com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED
                        && reasons.contains(carton.getRemovalReason()))
                .map(com.stockflow.fulfillment.internal.entity.PackageJpaEntity::getPickLineId)
                .collect(Collectors.toSet());
    }

    /**
     * A carton of the line {@code root} the check station removed and that waits to be put back whatever its task's
     * status: damaged (to quarantine, 4C-6), no longer wanted because the rest of its order was closed (to a bin,
     * WF4-CLOSE-REST), or good and out of a shipment cancelled before dispatch (to a bin, RTM-RTDCANCEL). A carton
     * removed with its order cancelled waits through its cancelled task instead.
     */
    private static jakarta.persistence.criteria.Subquery<UUID> removedToPutBack(
            jakarta.persistence.criteria.Root<PickLineJpaEntity> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        var carton = query.subquery(UUID.class);
        var row = carton.from(com.stockflow.fulfillment.internal.entity.PackageJpaEntity.class);
        carton.select(row.get("id")).where(cb.equal(row.get("pickLineId"), root.get("id")),
                cb.equal(row.get("status"), com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED),
                row.get("removalReason").in(
                        com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.DAMAGED,
                        com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.REMAINDER_CLOSED,
                        com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason.SHIPMENT_CANCELLED));
        return carton;
    }

    @Override
    public PickLineView reportShort(UUID taskId, UUID lineId, String locationCode, ShortPickReason reason,
                                    CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        // WF4-QLK-TAKEOVER (07 §2): the assignee reports a short - and so does whoever assigns the work, who may work
        // the task on behalf (this once covered a carton missing from the staging area only, review M2).
        TaskAccess.requireWorker(pick, actor);
        TaskAccess.orderInFulfilment(orders, pick);
        PickList.Confirmation outcome = list.reportShort(lineId, locationCode, reason);
        PickLine line = outcome.line();
        String barcode = barcodesOf(List.of(line.sku())).get(line.sku());
        if (outcome.repeated()) {
            return view(taskId, line, barcode);
        }
        writeBack(list, stored, Set.of(line.id()));
        // 07 §4.4 step 9, BR-09: the order stops (INVENTORY); it does not cancel, and no stock is deducted.
        holdForInventory(pick.getOrderId(), actor.userId(), "Short pick: carton %s (%d x %s) at %s, %s".formatted(
                line.lpn(), line.allocatedQty(), line.sku(), PickList.expectedAt(line), reason));
        // BR-09: the books and the shelf disagree, so the carton is counted blind (or the count holding it answers),
        // which also keeps it out of allocation until the variance is decided (20 BR-11). The picker took none of it.
        var recount = consumption.requestRecount(requireHold(line, "reported short"), line.id(), 0);
        log.info("Short pick line {}: recount {} of carton {}", line.id(), recount.countNumber(), line.lpn());
        log.info("Pick line {} of task {} short: carton {} ({} x {}) at {}, {}", line.id(), taskId, line.lpn(),
                line.allocatedQty(), line.sku(), PickList.expectedAt(line), reason);
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
        if (shortLine.status() != PickLineStatus.SHORT && removedReplaceable(List.of(lineId)).contains(lineId)) {
            // 08 §4.2 step 4: a carton removed at the check station is a shortage of its line, handled as a short pick;
            // so is one out of a shipment cancelled before dispatch (09 §5.1, RTM-RTDCANCEL).
            return replaceRemoved(pick, shortLine, actor);
        }
        // Every SHORT line has an allocation: one reported short names its carton.
        if (shortLine.status() != PickLineStatus.SHORT) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN,
                    "Only a SHORT line, or one whose carton was removed at the check station, is reallocated; it is "
                            + shortLine.status());
        }
        // Pick, then order, then stock rows. A cancellation locks the order before the stock rows it releases; taking
        // the order only at the end (replaceHold) would wait on it while holding those rows.
        orders.lockedStatus(pick.getOrderId());
        List<StockReservation> allocated = inventory.reallocate(new ReallocateStockCommand(pick.getOrderId(),
                shortLine.reservationId(), UUID.nameUUIDFromBytes(("reallocate:" + lineId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        orderReservations.replaceHold(pick.getOrderId(), shortLine.reservationId(),
                allocated.stream().map(StockReservation::reservationId).toList());
        Map<String, UUID> warehouseOf = new HashMap<>();
        // The new holds' cartons (SCRUM-473): every line names its carton (4C-4). A hold not read back would have none,
        // and PickLine refuses a line without one rather than plan a stop nobody could confirm by LPN.
        Map<UUID, HoldView> cartons = inventory.holds(allocated.stream().map(StockReservation::reservationId).toList())
                .stream().collect(Collectors.toMap(HoldView::reservationId, view -> view));
        List<PickList.Hold> holds = allocated.stream().map(hold -> {
            HoldView carton = cartons.get(hold.reservationId());
            return new PickList.Hold(hold.reservationId(), shortLine.sku(), hold.locationCode(),
                    warehouseOf.computeIfAbsent(hold.locationCode(),
                            code -> warehouses.findLocation(code).map(StorageLocationView::warehouseId).orElse(null)),
                    hold.lotNumber(), hold.quantity(), true, carton == null ? null : carton.lpn(),
                    carton != null && carton.wholeCarton());
        }).toList();
        List<PickLine> added = list.replaceShort(lineId, holds, Identifiers::newId);
        // The superseded line first: only one live line may hold an allocation (uk_pick_line_reservation_live), and a
        // flush inserts before it updates.
        writeBack(list, stored, Set.of(lineId));
        pickLines.flush();
        pickLines.saveAll(added.stream().map(line -> PickLineMapper.toNewEntity(pick.getId(), line)).toList());
        if (pick.getStatus() == PickStatus.STAGED) {
            // A carton short in the staging area (review M2): its replacements are picked, to the staging area again.
            pick.reopen();
            picks.save(pick);
        }
        if (!list.hasShortLines() && !lacksRemovedCartons(pick)) {
            resumeAfterInventory(pick, actor.userId(), "Short pick reallocated: line %s".formatted(lineId));
        }
        log.info("Short line {} of task {} reallocated to {} new line(s)", lineId, taskId, added.size());
        Map<String, String> barcodes = barcodesOf(List.of(shortLine.sku()));
        return added.stream().map(line -> view(taskId, line, barcodes.get(line.sku()))).toList();
    }

    @Override
    public void shortenForWrittenOffCarton(UUID orderId, UUID reservationId, UUID decidedBy) {
        var live = pickLines.findLiveByReservationId(reservationId);
        if (live.isEmpty()) {
            // No task opened for the order yet (a task's lines are planned from the live holds, so the given-up hold
            // would simply be missing from it): the order must not go to the floor short of a carton unnoticed.
            OrderSummary order = orders.findById(orderId).orElse(null);
            if (order != null && order.status() == com.stockflow.order.api.OrderStatus.READY_TO_FULFILL) {
                holdForInventoryIfHoldable(orderId, decidedBy, ("Carton counted missing: the order's "
                        + "allocation %s is a shortage").formatted(reservationId));
            }
            return;
        }
        // The pick before the order (and no stock row): a confirmation or a short report of the same task waits here.
        PickJpaEntity pick = lockPick(live.get().getPickId());
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        var turned = list.shortByCount(live.get().getId());
        if (turned.isEmpty()) {
            return;
        }
        PickLine line = turned.get();
        writeBack(list, stored, Set.of(line.id()));
        holdForInventoryIfHoldable(orderId, decidedBy, "Carton %s (%d x %s) counted missing at %s".formatted(line.lpn(),
                line.allocatedQty(), line.sku(), PickList.expectedAt(line)));
        log.info("Pick line {} of task {} short: its carton {} was written off by an approved count", line.id(),
                pick.getId(), line.lpn());
    }

    /**
     * A carton removed at the check station as damaged (08 §4.2 step 4), or out of a shipment cancelled before dispatch
     * (09 §5.1, RTM-RTDCANCEL), is replaced as a short pick is reallocated (07
     * §4.4 step 10): inventory allocates as many whole cartons from other pickable bins of the warehouse, FIFO, never the
     * removed one ({@code InventoryService#reallocateRemoved}); the order line holds them for the task's delivery batch,
     * and they become lines of the same task through the top-up ({@link #planLines}, 07 §4.3 step 7) - a PICKED task
     * goes back to PICKING, and the check session takes the replacement when it is picked. The removed line stays as it
     * is: its carton goes to quarantine through the put-back queue. An order held {@code INVENTORY} resumes once no line
     * is SHORT and no removed carton lacks a replacement. Locks: the pick, the order, then the stock rows.
     */
    private List<PickLineView> replaceRemoved(PickJpaEntity pick, PickLine removed, CurrentUser actor) {
        requireOpenForMore(pick);
        orders.lockedStatus(pick.getOrderId());
        OrderSummary order = orders.findById(pick.getOrderId()).orElseThrow();
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        UUID requestId = UUID.nameUUIDFromBytes(("replace-removed:" + removed.id())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Set<UUID> planned = list.lines().stream().map(PickLine::reservationId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        int lacking = lacking(pick, order, list).getOrDefault(removed.orderLineId(), 0);
        if (lacking < removed.allocatedQty()) {
            throw new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, ("Carton %s was replaced already: line %s of "
                    + "order %s lacks nothing more").formatted(removed.lpn(), removed.sku(), pick.getOrderNumber()));
        }
        List<UUID> allocated = inventory.reallocateRemoved(new ReallocateStockCommand(pick.getOrderId(),
                requireHold(removed, "replaced"), requestId)).stream().map(StockReservation::reservationId).toList();
        if (planned.containsAll(allocated)) {
            // The same request again: its replacement has its lines already.
            Map<String, String> barcodes = barcodesOf(List.of(removed.sku()));
            return list.lines().stream().filter(line -> allocated.contains(line.reservationId()))
                    .map(line -> view(pick.getId(), line, barcodes.get(line.sku()))).toList();
        }
        orderReservations.holdReplacement(pick.getOrderId(), removed.orderLineId(), pick.getDeliveryBatchId(),
                allocated);
        List<PickLine> added = planLines(pick, orders.findById(pick.getOrderId()).orElseThrow());
        Set<UUID> replacing = added.stream().filter(line -> allocated.contains(line.reservationId())).map(PickLine::id)
                .collect(Collectors.toSet());
        pickLines.findAllById(replacing).forEach(row -> {
            row.replaces(removed.id());
            pickLines.save(row);
        });
        if (!list.hasShortLines() && !lacksRemovedCartons(pick)) {
            resumeAfterInventory(pick, actor.userId(), "Removed carton %s replaced".formatted(removed.lpn()));
        }
        if (!added.isEmpty() && orders.findById(pick.getOrderId()).orElseThrow().status()
                != com.stockflow.order.api.OrderStatus.ON_HOLD) {
            // 07 §4.3 step 7, as a top-up: the order is picking again with its task (an order already picking, packed
            // or partly shipped is left as it is).
            orders.startPicking(pick.getOrderId(), actor.userId());
        }
        log.info("Removed carton {} of task {} replaced: {} new line(s)", removed.lpn(), pick.getId(), added.size());
        Map<String, String> barcodes = barcodesOf(added.stream().map(PickLine::sku).toList());
        return added.stream().map(line -> view(pick.getId(), line, barcodes.get(line.sku()))).toList();
    }

    @Override
    public void holdForUnreplacedCarton(UUID taskId, UUID lineId, CurrentUser actor) {
        holdForLackingCarton(taskId, lineId, actor, "Carton %s (%d x %s) removed at the check station: no whole carton "
                + "in the warehouse to replace it", "Order {} held INVENTORY: removed carton {} has no replacement in "
                + "stock");
    }

    @Override
    public void holdForRemovedCarton(UUID taskId, UUID lineId, CurrentUser actor) {
        holdForLackingCarton(taskId, lineId, actor, "Carton %s (%d x %s) removed at the check station: the warehouse "
                + "manager replaces it", "Order {} held INVENTORY: carton {} was removed at the check station");
    }

    @Override
    public void holdForCancelledShipment(UUID taskId, UUID lineId, String shipmentCode, CurrentUser actor) {
        holdForLackingCarton(taskId, lineId, actor, "Shipment " + shipmentCode.replace("%", "%%") + " cancelled before "
                + "dispatch: carton %s (%d x %s) is to be replaced by the warehouse manager",
                "Order {} held INVENTORY: carton {} left a shipment cancelled before dispatch");
    }

    /**
     * The order stops ({@code INVENTORY}) for a carton removed at the check station that no carton replaces yet, as the
     * order stops for a short pick report (07 §4.4 step 9, 11): Sales decides with the customer (17: wait for
     * production, agree fewer cartons, deliver in part and close the rest, or cancel). An order on hold already, a line
     * whose carton was not removed, or one replaced since, changes nothing.
     */
    private void holdForLackingCarton(UUID taskId, UUID lineId, CurrentUser actor, String noteFormat,
                                      String logFormat) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        if (!removedReplaceable(List.of(lineId)).contains(lineId)) {
            return;
        }
        PickList list = toList(pick, pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId()));
        PickLine removed = list.line(lineId).orElseThrow();
        if (lacking(pick, order, list).getOrDefault(removed.orderLineId(), 0) < removed.allocatedQty()) {
            return;
        }
        holdForInventory(pick.getOrderId(), actor.userId(), noteFormat.formatted(removed.lpn(), removed.allocatedQty(),
                removed.sku()));
        log.info(logFormat, pick.getOrderNumber(), removed.lpn());
    }

    /**
     * Per order line, the units the task still lacks: what it wants of the line (the station's count, 08 §4.4 step 8.3:
     * its delivery batch's units, or the line's quantity less what was closed) less the cartons its lines take - open,
     * staged, picked or short (a short line is its reallocation's business) - but neither a superseded line nor one
     * whose carton left the order to be replaced ({@link #REPLACEABLE}).
     */
    private Map<UUID, Integer> lacking(PickJpaEntity pick, OrderSummary order, PickList list) {
        Set<UUID> removed = removedReplaceable(list.lines().stream().map(PickLine::id).toList());
        Map<UUID, Integer> wanted = new HashMap<>();
        Map<UUID, Integer> batch = pick.getDeliveryBatchId() == null ? null
                : orders.batchUnits(order.orderId(), pick.getDeliveryBatchId());
        Map<UUID, Integer> closed = new HashMap<>();
        if (order.lifecycle() != null) {
            order.lifecycle().lines().forEach(p -> closed.put(p.lineId(), p.cancelledQuantity()));
        }
        order.lines().forEach(line -> wanted.put(line.lineId(), batch != null ? batch.getOrDefault(line.lineId(), 0)
                : Math.max(0, line.quantity() - closed.getOrDefault(line.lineId(), 0))));
        for (PickLine line : list.lines()) {
            boolean takes = switch (line.status()) {
                case OPEN, STAGED, SHORT -> true;
                case PICKED -> !removed.contains(line.id());
                case CANCELLED, PUT_BACK -> false;
            };
            if (takes) {
                wanted.computeIfPresent(line.orderLineId(), (id, units) -> units - line.allocatedQty());
            }
        }
        Map<UUID, Integer> lacking = new HashMap<>();
        wanted.forEach((line, units) -> lacking.put(line, Math.max(0, units)));
        return lacking;
    }

    /** Whether a line of the order whose carton left it to be replaced still lacks a replacement in the task. */
    private boolean lacksRemovedCartons(PickJpaEntity pick) {
        PickList list = toList(pick, pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId()));
        Set<UUID> removed = removedReplaceable(list.lines().stream().map(PickLine::id).toList());
        if (removed.isEmpty()) {
            return false;
        }
        Map<UUID, Integer> lacking = lacking(pick, orders.findById(pick.getOrderId()).orElseThrow(), list);
        return list.lines().stream().filter(line -> removed.contains(line.id()))
                .anyMatch(line -> lacking.getOrDefault(line.orderLineId(), 0) > 0);
    }

    /**
     * Lines join the task only while its check is not complete (review L1 of the top-up): once it is, its cartons left
     * with a shipment, and the rest of the order is a new delivery round. The one exception needs nothing here: a
     * shipment of the task cancelled before dispatch took its COMPLETED check back to IN_PROGRESS for the replacement
     * cartons (08 §5.1, 07 §4.3 step 7; RTM-RTDCANCEL, 2026-11-19).
     */
    private void requireOpenForMore(PickJpaEntity pick) {
        if (pick.getStatus() == PickStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The task was cancelled with its order");
        }
        packs.findByPickId(pick.getId()).filter(PickListService::checkLeft).ifPresent(pack -> {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, ("Packing of task %s is %s; its "
                    + "cartons left with a shipment, so nothing is added to it").formatted(pick.getId(),
                    pack.getStatus()));
        });
    }

    /**
     * The task's check is live and one of its shipments was cancelled: only the warehouse manager's cancellation before
     * dispatch leaves both (RTM-RTDCANCEL) - an order's cancellation cancels the check with its shipments.
     */
    private boolean replacingCancelledShipment(PickJpaEntity pick) {
        return packs.findByPickId(pick.getId()).filter(pack -> !checkLeft(pack))
                .map(pack -> shipments.existsByPackIdAndStatus(pack.getId(),
                        com.stockflow.fulfillment.internal.domain.ShipmentStatus.CANCELLED))
                .orElse(false);
    }

    /** {@link #requireOpenForMore} as a question: the read of the lines says whether a removed carton may be replaced. */
    private boolean isOpenForMore(PickJpaEntity pick) {
        return pick.getStatus() != PickStatus.CANCELLED
                && packs.findByPickId(pick.getId()).filter(PickListService::checkLeft).isEmpty();
    }

    /** The check of the task is complete (or cancelled): its cartons left with a shipment. */
    private static boolean checkLeft(com.stockflow.fulfillment.internal.entity.PackJpaEntity pack) {
        return pack.getStatus() != com.stockflow.fulfillment.internal.domain.PackStatus.PENDING
                && pack.getStatus() != com.stockflow.fulfillment.internal.domain.PackStatus.IN_PROGRESS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks the pick first, as every write of the task; reads the order's holds (inventory takes no lock to read
     * them). A top-up takes no stock lock: its lines only name cartons the order already holds.</p>
     */
    @Override
    public List<PickLineView> topUp(UUID taskId, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        // Review L1: a task whose packing is complete has handed its cartons on (a shipment); reopening its picking
        // would leave the pack finished under a task picking again. The rest of such an order is a new delivery round,
        // which docs 07 §4.1 step 2 gives a task of its own (4C-6 / the delivery schedule) - and an order has one task
        // here (fulfillment.pick.order_id is unique), so it is refused rather than opened as a second task.
        requireOpenForMore(pick);
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        if (pick.getDeliveryBatchId() != null && pick.getRedeliveryCaseId() == null
                && prePicking.topUp(pick.getOrderId(), pick.getDeliveryBatchId(), actor.userId())) {
            // 07 §4.3 step 7, 17 §4.4 step 16: a task opened before its order's release - the rest of its batch arrived,
            // it was held and the batch released (AWAITING_STOCK -> READY_TO_FULFILL). Read again for what follows.
            order = TaskAccess.orderInFulfilment(orders, pick);
        }
        List<PickLine> added = planLines(pick, order, actor.userId());
        if (!added.isEmpty() && order.status() != com.stockflow.order.api.OrderStatus.ON_HOLD
                && order.status() != com.stockflow.order.api.OrderStatus.AWAITING_STOCK) {
            // 07 §4.3 step 7: the order is picking again with its task (READY_TO_FULFILL -> PICKING; an order already
            // picking is left alone). An order on hold stays held: the hold resolves back to where it was. An order
            // still awaiting stock - part of the rest arrived, not all - stays so until its batch is held in full.
            orders.startPicking(pick.getOrderId(), actor.userId());
        }
        Map<String, String> barcodes = barcodesOf(added.stream().map(PickLine::sku).toList());
        return added.stream().map(line -> view(taskId, line, barcodes.get(line.sku()))).toList();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * X-SHORT step 02: ON_HOLD with reason INVENTORY (kltn-docs 17 edge "short pick"). An order already ON_HOLD - a
     * second short line, or held for another reason by the warehouse manager - is stopped already and stays as it is: a
     * hold is not stacked on a hold. Locks the order, so the caller keeps the order pick, order, stock rows.
     */
    private void holdForInventory(UUID orderId, UUID by, String note) {
        orders.lockedStatus(orderId);
        if (orders.findById(orderId).orElseThrow().status() != com.stockflow.order.api.OrderStatus.ON_HOLD) {
            orders.putOnHold(orderId, com.stockflow.order.api.HoldReason.INVENTORY, note, by);
        }
    }

    /**
     * {@link #holdForInventory}, for a shortage nobody reported at a bin: an order no longer in a status that can be
     * held (cancelled, shipped) has nothing to stop, and failing here would only make the event retry for ever.
     */
    private void holdForInventoryIfHoldable(UUID orderId, UUID by, String note) {
        var status = orders.lockedStatus(orderId);
        if (status.isPresent() && status.get().mayBePutOnHold()) {
            holdForInventory(orderId, by, note);
        }
    }

    /**
     * The last short line reallocated: the INVENTORY hold, and only that one, is resolved back to where it was - except
     * for a task whose shipment the warehouse manager cancelled before dispatch (RTM-RTDCANCEL; 09 §5.1 steps 4-5): there
     * the hold stays while the replacements are picked and checked in the reopened check, and completing that check in
     * full resolves it ({@code CartonCheckService#complete}).
     */
    private void resumeAfterInventory(PickJpaEntity pick, UUID by, String note) {
        if (replacingCancelledShipment(pick)) {
            log.info("Order {}: replacements of a cancelled shipment allocated; the hold waits for their check",
                    pick.getOrderNumber());
            return;
        }
        UUID orderId = pick.getOrderId();
        OrderSummary order = orders.findById(orderId).orElseThrow();
        if (order.status() == com.stockflow.order.api.OrderStatus.ON_HOLD && order.lifecycle() != null
                && order.lifecycle().holdReason() == com.stockflow.order.api.HoldReason.INVENTORY) {
            orders.resolveHold(orderId, note, by);
        }
    }

    /**
     * The hold behind a line that is being confirmed or reported short. Always there today - the line names a carton
     * (ck_pick_line_lpn) and tg_pick_line_carton makes it its hold's - but should either rule ever be relaxed, a line
     * without one must fail loudly: skipping would mark it PICKED with nothing deducted, or SHORT with no blind count
     * (07 BR-09). Thrown inside the transaction, so nothing of the request is kept.
     */
    private static UUID requireHold(PickLine line, String what) {
        if (line.reservationId() == null) {
            throw new IllegalStateException(("Pick line %s (carton %s) has no hold and cannot be %s: a line is planned "
                    + "from the hold on its carton").formatted(line.id(), line.lpn(), what));
        }
        return line.reservationId();
    }

    private PickJpaEntity lockPick(UUID taskId) {
        return picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static PickList toList(PickJpaEntity pick, List<PickLineJpaEntity> stored) {
        return new PickList(pick.getId(), pick.getWarehouseId(), pick.getStatus(), pick.isNeedsPutBack(),
                pick.confirmsInto(), stored.stream().map(PickLineMapper::toDomain).toList());
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
                .map(line -> new PickList.OrderLine(line.lineId(), line.sku(), line.reservationIds()))
                .toList();
    }

    /**
     * The holds of the order's lines, with the warehouse each one's location belongs to - allocations only: a SKU
     * reservation is on no shelf yet. It stands among a task's holds only for an order picked before its release (07
     * §4.1 step 2): goods held for its batch since, which a top-up allocates first ({@link #topUp}).
     */
    private Map<UUID, PickList.Hold> holdsOf(PickJpaEntity pick, OrderSummary order) {
        Set<UUID> ids = taskHoldIds(pick, order);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> warehouseOf = new HashMap<>();
        Map<UUID, PickList.Hold> holds = new HashMap<>();
        for (HoldView hold : inventory.holds(ids)) {
            if (hold.locationCode() == null) {
                continue;
            }
            UUID where = warehouseOf.computeIfAbsent(hold.locationCode(), code -> warehouses.findLocation(code)
                    .map(StorageLocationView::warehouseId).orElse(null));
            holds.put(hold.reservationId(), new PickList.Hold(hold.reservationId(), hold.sku(), hold.locationCode(),
                    where, hold.lotNumber(), hold.quantity(), hold.status() == HoldView.Status.HELD, hold.lpn(),
                    hold.wholeCarton()));
        }
        return holds;
    }

    /**
     * 07 §4.3 step 7: whether a top-up would add to the task - it is STAGED or PICKED, its check not complete, and the
     * order holds cartons for it (a SKU reservation of its batch held since, or an allocation) that no line takes yet.
     */
    @Transactional(readOnly = true)
    public boolean topUpAvailable(PickJpaEntity pick, OrderSummary order) {
        if (pick.getStatus() != PickStatus.STAGED && pick.getStatus() != PickStatus.PICKED || !isOpenForMore(pick)) {
            return false;
        }
        Set<UUID> ids = taskHoldIds(pick, order);
        pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId()).stream()
                .map(PickLineJpaEntity::getReservationId).filter(java.util.Objects::nonNull).forEach(ids::remove);
        return !ids.isEmpty() && inventory.holds(ids).stream().anyMatch(hold -> hold.status() == HoldView.Status.HELD);
    }

    /** The ids of the holds the task picks: the order's, narrowed to its redelivery or its delivery batch. */
    private Set<UUID> taskHoldIds(PickJpaEntity pick, OrderSummary order) {
        Set<UUID> ids = order.lines().stream()
                .flatMap(line -> line.reservationIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (pick.getRedeliveryCaseId() != null) {
            // WF-05 fix plan, section 2: a redelivery's task picks the goods held for its delivery failure case - not
            // the batch's first holds (picked long ago) nor a later batch's.
            ids.retainAll(orders.redelivery(pick.getRedeliveryCaseId())
                    .map(com.stockflow.order.api.RedeliveryToPick::holdIds).orElse(Set.of()));
        } else if (pick.getDeliveryBatchId() != null) {
            // 4B-8 (07 BR-07): a task picks its own delivery batch - the holds that stand for it, not the order's
            // others (a later batch's holds wait at the warehouse for their own release).
            ids.retainAll(orders.holdsOfBatch(order.orderId(), pick.getDeliveryBatchId()));
        }
        return ids;
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
                line.pickedAt(), line.putBackQty(), line.putBackLocationCode(), line.putBackAt(), line.shortQty(),
                line.shortReason(), line.lpn(), line.wholeCarton(), line.stagedAt(), line.stagedLocationCode());
    }
}
