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
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.consumption.DamagePhoto;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.PickDamageReporting;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.consumption.ReportPickDamageCommand;
import com.stockflow.inventory.consumption.ReportPickDamageResult;
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
 * therefore serialised on the pick, and the second sees the line PICKED. A write that also changes the order
 * (a short report, a reallocation) locks it after the pick and before any stock row, because a cancellation
 * locks the order first and the stock rows second.</p>
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
    private final PickDamageReporting damages;


    PickListService(PickJpaRepository picks, PickLineJpaRepository pickLines, OrderService orders,
                    InventoryService inventory, InventoryControlService itemControls, PickConsumption consumption,
                    WarehouseService warehouses, Clock clock, OrderReservations orderReservations,
                    PickDamageReporting damages) {
        this.damages = damages;
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
            return cb.and(cb.equal(root.get("status"), PickLineStatus.PICKED),
                    cb.gt(root.get("pickedQty"), root.<Integer>get("packingDamagedQty")), cb.exists(task));
        };
        Page<PickLineJpaEntity> found = pickLines.findAll(waiting,
                Pages.of(page, size, Sort.by(Sort.Direction.ASC, "pickedAt", "id")));
        Map<UUID, PickJpaEntity> tasks = picks.findAllById(found.getContent().stream()
                        .map(PickLineJpaEntity::getPickId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PickJpaEntity::getId, Function.identity()));
        return Pages.toResponse(found, row -> {
            PickJpaEntity task = tasks.get(row.getPickId());
            return new PutBackRow(row.getPickId(), task.getOrderNumber(), task.getWarehouseId(), row.getId(),
                    row.getSku(), row.getLotNumber(), row.getGoodQty(), row.getLocationCode(), row.getPickedAt());
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
        holdForInventory(pick.getOrderId(), actor.userId(), "Short pick: %d of %s x %s missing at %s".formatted(
                line.shortQty(), line.allocatedQty(), line.sku(), line.locationCode()));
        // X-SHORT step 03: the books and the shelf disagree, so the bin is counted again.
        if (line.reservationId() != null) {
            var recount = consumption.requestRecount(line.reservationId(), line.id(),
                    line.allocatedQty() - line.shortQty());
            log.info("Short pick line {}: recount {} of {}", line.id(), recount.countNumber(), line.locationCode());
        }
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
        // Pick, then order, then stock rows. A cancellation locks the order before the stock rows it releases; taking
        // the order only at the end (replaceHold, resolveDesignHold) would wait on it while holding those rows.
        orders.lockedStatus(pick.getOrderId());
        List<StockReservation> allocated = inventory.reallocate(new ReallocateStockCommand(pick.getOrderId(),
                shortLine.reservationId(), UUID.nameUUIDFromBytes(("reallocate:" + lineId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        orderReservations.replaceHold(pick.getOrderId(), shortLine.reservationId(),
                allocated.stream().map(StockReservation::reservationId).toList());
        Map<String, UUID> warehouseOf = new HashMap<>();
        // The new holds' cartons (SCRUM-473): a line drawn from a carton must name it. A hold not read back is planned
        // loose; were it in a carton after all, tg_pick_line_carton refuses the line rather than let it go unscanned.
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
        // The superseded line first: a restored impaired allocation is kept, so the new line holds the same allocation,
        // and only one live line may (uk_pick_line_reservation_live) - a flush inserts before it updates.
        writeBack(list, stored, Set.of(lineId));
        pickLines.flush();
        pickLines.saveAll(added.stream().map(line -> PickLineMapper.toNewEntity(pick.getId(), line)).toList());
        if (!list.hasShortLines()) {
            resumeAfterInventory(pick.getOrderId(), actor.userId(), "Short pick reallocated: line %s".formatted(lineId));
        }
        log.info("Short line {} of task {} reallocated to {} new line(s)", lineId, taskId, added.size());
        Map<String, String> barcodes = barcodesOf(List.of(shortLine.sku()));
        return added.stream().map(line -> view(taskId, line, barcodes.get(line.sku()))).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks: the pick first, as every write of the task, so two scans of one line - a confirmation and a damage
     * report, or the same report twice - are taken one after the other and the second sees the line as the first left
     * it. The photos are stored under the pick's lock only, before the order and the stock rows are locked: an upload
     * never holds a lock a checkout or another picker waits on, and they are deleted again if anything after them
     * fails. Then the order (a cancellation locks the order before the stock rows it releases), then inventory's - the
     * SKU, the carton, the stock rows. The order line learns its new holds and, when nothing replaced the damaged
     * units, goes ON_HOLD, under the order's lock already held.</p>
     *
     * <p>The same report sent again is recognised from the line itself (superseded by a damage report of that many
     * units at that bin) before anything is stored, and answered whatever happened to the task since.</p>
     */
    @Override
    public PickDamageView reportDamage(UUID taskId, UUID lineId, PickDamage damage, List<FileUpload> photos,
                                       CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        if (list.alreadyReportedDamaged(lineId, damage.locationCode(), damage.damagedQuantity())) {
            return damageView(taskId, list, list.line(lineId).orElseThrow());
        }
        TaskAccess.orderInFulfilment(orders, pick);
        list.requireDamageable(lineId, damage.locationCode(), damage.damagedQuantity());
        PickLine line = list.line(lineId).orElseThrow();
        List<DamagePhoto> evidence = damages.storePhotos(photos);
        // Pick, then order, then stock rows - the order read again under its lock: cancelled while the photos were
        // stored, it gets nothing allocated.
        TaskAccess.requireStillInFulfilment(orders, pick);
        ReportPickDamageResult result = damages.report(new ReportPickDamageCommand(damage.requestId(),
                line.reservationId(), line.id(), pick.getOrderId(), damage.damagedQuantity(), damage.reason(),
                damage.note(), evidence, damage.damageLocationCode(), actor.userId()));

        List<PickList.Hold> replacements = replacementHolds(line.sku(), result);
        List<PickLine> added = list.replaceDamaged(lineId, damage.damagedQuantity(), result.damageReportId(),
                result.retainedQuantity(), replacements, result.impairedReservationId(), Identifiers::newId);
        // The superseded line first: the line for the good units takes over its allocation, and only one live line
        // may hold an allocation (uk_pick_line_reservation_live) - a flush inserts before it updates.
        writeBack(list, stored, Set.of(lineId));
        pickLines.flush();
        pickLines.saveAll(added.stream().map(row -> PickLineMapper.toNewEntity(pick.getId(), row)).toList());
        if (!result.holdsAfter().equals(List.of(line.reservationId()))) {
            orderReservations.replaceHold(pick.getOrderId(), line.reservationId(), result.holdsAfter());
        }
        boolean held = result.impairedReservationId() != null;
        if (held) {
            // X-SHORT step 02: nothing replaced the damaged units, so the order stops until the coordinator decides.
            holdForInventory(pick.getOrderId(), actor.userId(), ("Damaged at pick: %d x %s at %s, report %s; no other "
                    + "stock to replace them").formatted(damage.damagedQuantity(), line.sku(), line.locationCode(),
                    result.reportNumber()));
        }
        log.info("Pick line {} of task {}: {} of {} x {} damaged at {} (report {}), {} new line(s){}", lineId, taskId,
                damage.damagedQuantity(), line.allocatedQty(), line.sku(), line.locationCode(), result.reportNumber(),
                added.size(), held ? ", order on hold" : "");
        Map<String, String> barcodes = barcodesOf(List.of(line.sku()));
        return new PickDamageView(result.damageReportId(), result.reportNumber(), result.damageLocationCode(),
                result.quantity(), held, view(taskId, list.line(lineId).orElseThrow(), barcodes.get(line.sku())),
                added.stream().map(row -> view(taskId, row, barcodes.get(row.sku()))).toList());
    }

    // ------------------------------------------------------------------ damage found at packing (R8), for PackingService

    /**
     * The same report of damage at packing sent again: what it made, as it stands now - whatever happened to the task
     * since. Empty when the request reported nothing. The caller holds the pick.
     *
     * @throws BusinessException {@code CONFLICT} when the request made a report of something else
     */
    Optional<PickDamageView> damageAfterPickReplay(PickJpaEntity pick, UUID lineId, UUID requestId) {
        Optional<ReportPickDamageResult> report = damages.reportedFor(requestId);
        if (report.isEmpty()) {
            return Optional.empty();
        }
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        UUID reportId = report.get().damageReportId();
        PickLine line = list.line(lineId).orElse(null);
        if (line == null || !list.madeAfterPick(reportId) || !lineId.equals(report.get().pickLineId())) {
            throw new BusinessException(ErrorCode.CONFLICT, ("Request %s made damage report %s, not one at packing of "
                    + "this line").formatted(requestId, report.get().reportNumber()));
        }
        Map<String, String> barcodes = barcodesOf(List.of(line.sku()));
        return Optional.of(new PickDamageView(reportId, report.get().reportNumber(),
                report.get().damageLocationCode(), report.get().quantity(),
                report.get().impairedReservationId() != null, view(pick.getId(), line, barcodes.get(line.sku())),
                list.madeByDamage(reportId).stream().map(row -> view(pick.getId(), row, barcodes.get(row.sku())))
                        .toList()));
    }

    /** R8: the line can be reported damaged at packing, as far as the pick list knows. The caller holds the pick. */
    void requireDamageableAfterPick(PickJpaEntity pick, UUID lineId, int damaged) {
        toList(pick, pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId()))
                .requireDamageableAfterPick(lineId, damaged);
    }

    /**
     * R8, the pick list's side: the caller (packing) holds the pick and checked that the units are out of every parcel.
     * The task is PICKING (the units were in a tote) or PICKED (at the bench); a PICKED one goes back to PICKING.
     * Photos first, under the pick's lock only; then the order; then inventory's locks. The picked line keeps the
     * damaged units on record, the lines the report made are added, the task goes back to PICKING, and the order line
     * holds the replacements (or the impaired share) next to the hold the pick consumed - which still names the good
     * units that reached packing. With nothing to replace them, the order goes ON_HOLD (INVENTORY) as at picking.
     */
    PickDamageView damageAfterPick(PickJpaEntity pick, UUID lineId, Packing.PackDamage damage,
                                   List<FileUpload> photos, CurrentUser actor) {
        List<PickLineJpaEntity> stored = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        PickList list = toList(pick, stored);
        list.requireDamageableAfterPick(lineId, damage.damagedQuantity());
        PickLine line = list.line(lineId).orElseThrow();
        List<DamagePhoto> evidence = damages.storePhotos(photos);
        // Pick, then order (read again under its lock, as at picking), then stock rows.
        TaskAccess.requireStillInFulfilment(orders, pick);
        ReportPickDamageResult result = damages.reportAfterPick(new ReportPickDamageCommand(damage.requestId(),
                line.reservationId(), line.id(), pick.getOrderId(), damage.damagedQuantity(), damage.reason(),
                damage.note(), evidence, damage.damageLocationCode(), actor.userId()));

        List<PickList.Hold> replacements = replacementHolds(line.sku(), result);
        List<PickLine> added = list.replaceDamagedAfterPick(lineId, damage.damagedQuantity(), result.damageReportId(),
                replacements, result.impairedReservationId(), Identifiers::newId);
        writeBack(list, stored, Set.of(lineId));
        pickLines.saveAll(added.stream().map(row -> PickLineMapper.toNewEntity(pick.getId(), row)).toList());
        if (pick.getStatus() == PickStatus.PICKED) {
            pick.reopen();
            picks.save(pick);
        }
        List<UUID> holds = new ArrayList<>();
        holds.add(line.reservationId());
        holds.addAll(result.holdsAfter());
        orderReservations.replaceHold(pick.getOrderId(), line.reservationId(), holds);
        boolean held = result.impairedReservationId() != null;
        if (held) {
            holdForInventory(pick.getOrderId(), actor.userId(), ("Damaged at packing: %d x %s picked at %s, report %s; "
                    + "no other stock to replace them").formatted(damage.damagedQuantity(), line.sku(),
                    line.locationCode(), result.reportNumber()));
        }
        log.info("Pick line {} of task {}: {} of {} picked x {} damaged at packing (report {}), {} new line(s), task "
                        + "back to picking{}", lineId, pick.getId(), damage.damagedQuantity(), line.pickedQty(),
                line.sku(), result.reportNumber(), added.size(), held ? ", order on hold" : "");
        Map<String, String> barcodes = barcodesOf(List.of(line.sku()));
        return new PickDamageView(result.damageReportId(), result.reportNumber(), result.damageLocationCode(),
                result.quantity(), held, view(pick.getId(), list.line(lineId).orElseThrow(), barcodes.get(line.sku())),
                added.stream().map(row -> view(pick.getId(), row, barcodes.get(row.sku()))).toList());
    }

    /** A report already applied, answered with the lines it made as they stand now and where the units went. */
    private PickDamageView damageView(UUID taskId, PickList list, PickLine superseded) {
        List<PickLine> made = list.madeByDamage(superseded.damageReportId());
        Optional<ReportPickDamageResult> report = damages.reported(superseded.damageReportId());
        Map<String, String> barcodes = barcodesOf(List.of(superseded.sku()));
        return new PickDamageView(superseded.damageReportId(),
                report.map(ReportPickDamageResult::reportNumber).orElse(null),
                report.map(ReportPickDamageResult::damageLocationCode).orElse(null), superseded.damagedQty(),
                report.map(r -> r.impairedReservationId() != null).orElse(false),
                view(taskId, superseded, barcodes.get(superseded.sku())),
                made.stream().map(row -> view(taskId, row, barcodes.get(row.sku()))).toList());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * X-SHORT step 02: ON_HOLD with reason INVENTORY (kltn-docs 17 edge "short pick"). An order already ON_HOLD - a
     * second short line, or held for another reason by the coordinator - is stopped already and stays as it is: a
     * hold is not stacked on a hold. Locks the order, so the caller keeps the order pick, order, stock rows.
     */
    private void holdForInventory(UUID orderId, UUID by, String note) {
        orders.lockedStatus(orderId);
        if (orders.findById(orderId).orElseThrow().status() != com.stockflow.order.api.OrderStatus.ON_HOLD) {
            orders.putOnHold(orderId, com.stockflow.order.api.HoldReason.INVENTORY, note, by);
        }
    }

    /** The last short line reallocated: the INVENTORY hold, and only that one, is resolved back to where it was. */
    private void resumeAfterInventory(UUID orderId, UUID by, String note) {
        OrderSummary order = orders.findById(orderId).orElseThrow();
        if (order.status() == com.stockflow.order.api.OrderStatus.ON_HOLD && order.lifecycle() != null
                && order.lifecycle().holdReason() == com.stockflow.order.api.HoldReason.INVENTORY) {
            orders.resolveHold(orderId, note, by);
        }
    }

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

    /**
     * The allocations that replaced damaged units, as the pick list plans them: with the warehouse of each location and
     * the carton each is in (SCRUM-473), read back as the short reallocation does. A hold not read back is planned
     * loose; were it in a carton after all, tg_pick_line_carton refuses the line rather than let it go unscanned.
     */
    private List<PickList.Hold> replacementHolds(String sku, ReportPickDamageResult result) {
        if (result.replacements().isEmpty()) {
            return List.of();
        }
        Map<UUID, HoldView> cartons = inventory.holds(result.replacements().stream()
                        .map(ReportPickDamageResult.Allocation::reservationId).toList())
                .stream().collect(Collectors.toMap(HoldView::reservationId, view -> view));
        Map<String, UUID> warehouseOf = new HashMap<>();
        return result.replacements().stream().map(hold -> {
            HoldView carton = cartons.get(hold.reservationId());
            return new PickList.Hold(hold.reservationId(), sku, hold.locationCode(),
                    warehouseOf.computeIfAbsent(hold.locationCode(),
                            code -> warehouses.findLocation(code).map(StorageLocationView::warehouseId).orElse(null)),
                    hold.lotNumber(), hold.quantity(), true, carton == null ? null : carton.lpn(),
                    carton != null && carton.wholeCarton());
        }).toList();
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
                    where, hold.lotNumber(), hold.quantity(), hold.status() == HoldView.Status.HELD, hold.lpn(),
                    hold.wholeCarton()));
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
                line.pickedAt(), line.putBackQty(), line.putBackLocationCode(), line.putBackAt(), line.shortQty(),
                line.lpn(), line.wholeCarton(), line.damagedQty(), line.damageReportId(), line.packingDamagedQty());
    }
}
