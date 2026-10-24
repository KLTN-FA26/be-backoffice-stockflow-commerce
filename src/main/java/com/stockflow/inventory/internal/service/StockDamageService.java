package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.consumption.DamagePhoto;
import com.stockflow.inventory.consumption.PickDamageReporting;
import com.stockflow.inventory.consumption.ReportPickDamageCommand;
import com.stockflow.inventory.consumption.ReportPickDamageResult;
import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.DamageReason;
import com.stockflow.inventory.internal.domain.ExpiryPolicy;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import com.stockflow.inventory.internal.domain.StockDamageReportRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLots;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockDamageReportSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Damaged stock, end to end (SCRUM-475 shelf reports, SCRUM-277 pick reports; docs 03 §5, BR-STK-003): the units
 * leave AVAILABLE for a quarantine area as DAMAGED, the report keeps the evidence, and a warehouse manager decides.
 *
 * <p><b>Where damaged goods go.</b> To a QUARANTINE area of the same warehouse, never "DAMAGED at the bin": a receipt
 * layer has one row per location whatever its status ({@code uk_stock_item_sku_location_lot}), so units of a layer
 * cannot be AVAILABLE and DAMAGED at one bin, and damaged goods belong apart from good ones on the floor as well
 * (docs 03 §3 sends QC's held and rejected goods to the same kind of area). The reporter names the area, or the
 * first usable one by code that can take these goods.</p>
 *
 * <p><b>Holds are never broken behind their owner's back.</b> A shelf report takes free units only: a hold names
 * units somebody will take, and they are reported where they are taken. A picker's report takes units of the
 * allocation they were picking, and the order is made whole in the same transaction - the same quantity allocated
 * again, FEFO, on pickable stock of the warehouse (the bin included: its count is not in doubt, unlike a short
 * pick's), or, when there is none, kept as an impaired allocation on the damaged units: never pickable, and the
 * picker's line goes SHORT and the order ON_HOLD for the coordinator (X-SHORT). Checkout reservations hold no
 * units; when the damage leaves them promising more than the free stock, {@code StockDamageReported} says by how much.</p>
 *
 * <p><b>Locks</b>, in the order every stock writer keeps: the SKU's advisory lock, the carton of the source row,
 * then every stock row this change touches - the source, the damaged row in quarantine, the rows replacements are
 * allocated on - once each, in id order. A picker's caller (fulfillment) holds the pick and the order before this.
 * Photos are stored before any lock: nobody waits on an upload.</p>
 *
 * <p><b>Ledger.</b> One {@code MOVE} line per report (source → quarantine, AVAILABLE → DAMAGED, reference
 * {@code DAMAGE_REPORT}); a write-off posts a DAMAGED {@code ADJUSTMENT}; a restore moves the units back with a
 * {@code MOVE} line, DAMAGED → AVAILABLE.</p>
 */
@Service
@Transactional
class StockDamageService implements StockDamages, PickDamageReporting {

    private static final Logger log = LoggerFactory.getLogger(StockDamageService.class);

    private static final SortWhitelist SORT = SortWhitelist.of("reportedAt", "quantity")
            .withDefault("reportedAt", Sort.Direction.DESC);

    private final StockItemRepository stockItems;
    private final StockDamageReportRepository reports;
    private final StockDamageReportSearch search;
    private final StockAdjustmentRepository adjustments;
    private final StockMovementLog ledger;
    private final LocationDirectory locations;
    private final InventoryPolicyRepository policies;
    private final StockPackageContents cartons;
    private final InventoryEventPublisher events;
    private final SkuReservationRepository skuReservations;
    private final CycleCountRepository cycleCounts;
    private final InventoryControlService controls;
    private final StockLots lots;
    private final DamageEvidence evidence;
    private final Clock clock;
    private final PickableStock pickable;

    @SuppressWarnings("java:S107")
    StockDamageService(StockItemRepository stockItems, StockDamageReportRepository reports,
                       StockDamageReportSearch search, StockAdjustmentRepository adjustments, StockMovementLog ledger,
                       LocationDirectory locations, InventoryPolicyRepository policies, StockPackageContents cartons,
                       InventoryEventPublisher events, SkuReservationRepository skuReservations, CycleCountRepository cycleCounts,
                       InventoryControlService controls, StockLots lots, DamageEvidence evidence, Clock clock) {
        this.stockItems = stockItems;
        this.reports = reports;
        this.search = search;
        this.adjustments = adjustments;
        this.ledger = ledger;
        this.locations = locations;
        this.policies = policies;
        this.cartons = cartons;
        this.events = events;
        this.skuReservations = skuReservations;
        this.cycleCounts = cycleCounts;
        this.controls = controls;
        this.lots = lots;
        this.evidence = evidence;
        this.clock = clock;
        this.pickable = new PickableStock(stockItems, locations, cycleCounts);
    }

    // ================================================================== a shelf report (SCRUM-475)

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-damage-report", resourceId = "#result?.id()")
    public DamageReportView report(ReportDamageCommand command, List<FileUpload> uploads) {
        Optional<StockDamageReport> done = reports.findByRequestId(command.requestId());
        if (done.isPresent()) {
            WarehouseScope.requireWarehouse(done.get().goods().warehouseId());
            return view(done.get());
        }
        StockItem view = stockItems.findById(new StockItemId(command.stockItemId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No stock item " + command.stockItemId()));
        // BR-SEC-002 first: another warehouse's stock is not this caller's to report, nor to learn about.
        WarehouseScope.requireLocation(view.location().code());
        StockDamageReport.requireExplained(command.reason(), command.note());
        List<StockDamageReport.Photo> photos = evidence.store(uploads);

        policies.lock(view.sku().code());
        Optional<StockDamageReport> raced = reports.findByRequestId(command.requestId());
        if (raced.isPresent()) {
            // The same request committed while this one stored its photos: answer with it, keep nothing of ours.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return view(raced.get());
        }
        UUID warehouseId = warehouseOf(view.location());
        Destination destination = destination(view, warehouseId, command.damageLocationCode());
        StockPackageContents.LockedCartons lockedCartons = cartons.lockFor(List.of(view));
        TreeMap<String, StockItem> locked = lock(view.id(), destination.existing(), List.of());
        StockItem source = locked.get(view.id().value().toString());
        Instant now = clock.instant();
        Quantity quantity = Quantity.of(command.quantity());

        lockedCartons.tookFrom(source);
        source.giveUpDamaged(quantity, now);
        StockItem damaged = destination.existing() == null
                ? StockItem.emptyLayerOf(source, destination.location(), StockStatus.DAMAGED)
                : locked.get(destination.existing().value().toString());
        damaged.takeUpDamaged(source, quantity, null, null, now);
        saveMoved(source, damaged, destination.existing() != null);
        cartons.settle(lockedCartons, now);

        StockDamageReport report = StockDamageReport.report(reports.nextReportNumber(BusinessCalendar.date(now)),
                command.requestId(), StockDamageReport.DamagedGoods.of(source, warehouseId), damaged.id(),
                damaged.location(), quantity, command.reason(), command.note(), photos, null, command.reportedBy(),
                now, shortfall(source.sku(), now));
        StockDamageReport saved = reports.save(report);
        events.publishEventsOf(report);
        ledger.append(movedToQuarantine(report, source, damaged, now));
        log.info("Damage report {}: {} x {}{} at {} moved to {} as DAMAGED ({})", report.number(), quantity,
                source.sku(), lotSuffix(source.lotNumber()), source.location(), damaged.location(), command.reason());
        return view(saved);
    }

    // ================================================================== a pick report (SCRUM-277)

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DamagePhoto> storePhotos(List<FileUpload> photos) {
        return evidence.store(photos).stream().map(photo -> new DamagePhoto(photo.storageKey(), photo.originalName(),
                photo.contentType(), photo.sizeBytes(), photo.checksum())).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-damage-report",
            resourceId = "#result?.damageReportId()")
    public ReportPickDamageResult report(ReportPickDamageCommand command) {
        Optional<StockDamageReport> done = reports.findByRequestId(command.requestId());
        if (done.isPresent()) {
            WarehouseScope.requireWarehouse(done.get().goods().warehouseId());
            return pickResult(done.get(), true);
        }
        DamageReason reason = DamageReason.valueOf(command.reason().name());
        StockDamageReport.requireExplained(reason, command.note());
        if (command.photos().isEmpty()) {
            throw new BusinessException(ErrorCode.DAMAGE_PHOTO_REQUIRED,
                    "Damaged stock is reported with at least one photo (BR-STK-003)");
        }
        ReservationId holdId = ReservationId.of(command.reservationId());
        StockItem first = holder(holdId);
        WarehouseScope.requireLocation(first.location().code());
        policies.lock(first.sku().code());
        // Current under the SKU lock: a hold changes row only under it.
        StockItem view = holder(holdId);
        Reservation hold = view.findReservation(holdId).orElseThrow();
        if (!command.orderId().equals(hold.orderId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                    "Allocation %s is not order %s's".formatted(command.reservationId(), command.orderId()));
        }
        Instant now = clock.instant();
        Quantity quantity = Quantity.of(command.quantity());
        UUID warehouseId = warehouseOf(view.location());
        Destination destination = destination(view, warehouseId, command.damageLocationCode());
        List<StockAllocator.AllocationLine> replacement = planReplacement(view, quantity, now);

        StockPackageContents.LockedCartons lockedCartons = cartons.lockFor(List.of(view));
        TreeMap<String, StockItem> locked = lock(view.id(), destination.existing(),
                replacement.stream().map(StockAllocator.AllocationLine::stockItemId).toList());
        StockItem source = locked.get(view.id().value().toString());

        StockItem.HeldShare share = source.giveUpDamagedHeld(holdId, quantity, now);
        lockedCartons.tookFrom(source);
        StockItem damaged = destination.existing() == null
                ? StockItem.emptyLayerOf(source, destination.location(), StockStatus.DAMAGED)
                : locked.get(destination.existing().value().toString());
        List<ReportPickDamageResult.Allocation> replacements = new ArrayList<>();
        Reservation impaired = null;
        if (replacement.isEmpty()) {
            impaired = damaged.takeUpDamaged(source, quantity, share, derived("damage-impaired", command.requestId(),
                    damaged.id()), now);
        } else {
            damaged.takeUpDamaged(source, quantity, null, null, now);
            for (StockAllocator.AllocationLine line : replacement) {
                StockItem row = locked.get(line.stockItemId().value().toString());
                Reservation allocation = row.allocate(share.orderId(), share.allocatedFrom(), command.requestId(),
                        derived("damage-replacement", command.requestId(), row.id()), line.quantity(), now);
                replacements.add(new ReportPickDamageResult.Allocation(allocation.id().value(), row.location().code(),
                        row.lotNumber(), allocation.quantity().value()));
            }
        }
        saveMoved(source, damaged, destination.existing() != null);
        for (StockItem row : locked.values()) {
            if (!row.id().equals(source.id()) && !row.id().equals(damaged.id())) {
                stockItems.save(row);
            }
            events.publishEventsOf(row);
        }
        cartons.settle(lockedCartons, now);

        StockDamageReport report = StockDamageReport.report(reports.nextReportNumber(BusinessCalendar.date(now)),
                command.requestId(), StockDamageReport.DamagedGoods.of(source, warehouseId), damaged.id(),
                damaged.location(), quantity, reason, command.note(),
                photos(command.photos()), new StockDamageReport.PickContext(command.orderId(), command.pickLineId(),
                        holdId.value(), impaired == null ? null : impaired.id().value()),
                command.actorId(), now, shortfall(source.sku(), now));
        reports.save(report);
        events.publishEventsOf(report);
        ledger.append(movedToQuarantine(report, source, damaged, now).forOrder(command.orderId()));

        int retained = source.findReservation(holdId).filter(r -> r.status().isActive())
                .map(r -> r.quantity().value()).orElse(0);
        log.info("Damage report {} at picking: {} x {} of allocation {} (order {}) moved to {}; {}", report.number(),
                quantity, source.sku(), holdId, command.orderId(), damaged.location(), impaired == null
                        ? "replaced by %d allocation(s)".formatted(replacements.size())
                        : "no other stock, the share stays impaired as " + impaired.id());
        return new ReportPickDamageResult(report.id(), report.number(), damaged.location().code(), quantity.value(),
                holdId.value(), retained, impaired == null ? null : impaired.id().value(), replacements,
                holdsAfter(holdId, retained, impaired, replacements), false);
    }

    /**
     * The order's replacement for damaged units, FEFO over pickable stock of the warehouse, read before any lock (the
     * SKU lock keeps it valid: every path that changes availability takes it first). Empty when the warehouse cannot
     * cover all of them: the order then keeps an impaired share, never half a replacement.
     */
    private List<StockAllocator.AllocationLine> planReplacement(StockItem source, Quantity quantity, Instant now) {
        List<StockAllocator.Candidate> pool = pickable.candidates(source.sku(), source.location().warehouseCode(),
                null, now);
        try {
            return StockAllocator.plan(source.sku().code(), pool, quantity,
                    controls.policy(source.sku()).removalStrategy(), BusinessCalendar.date(now));
        } catch (BusinessException noReplacement) {
            log.info("No replacement for {} damaged x {} in {}: {}", quantity, source.sku(),
                    source.location().warehouseCode(), noReplacement.getMessage());
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportPickDamageResult> reported(UUID damageReportId) {
        return reports.findById(damageReportId).filter(report -> report.pick() != null)
                .map(report -> {
                    WarehouseScope.requireWarehouse(report.goods().warehouseId());
                    return pickResult(report, true);
                });
    }

    /** A report this request made before, answered as it stands now. */
    private ReportPickDamageResult pickResult(StockDamageReport report, boolean already) {
        StockDamageReport.PickContext pick = report.pick();
        if (pick == null) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Request %s made damage report %s on a shelf, not at picking".formatted(report.requestId(),
                            report.number()));
        }
        ReservationId holdId = ReservationId.of(pick.reservationId());
        int retained = stockItems.findIdByReservationId(holdId).flatMap(stockItems::findById)
                .flatMap(item -> item.findReservation(holdId)).filter(r -> r.status().isActive())
                .map(r -> r.quantity().value()).orElse(0);
        List<ReportPickDamageResult.Allocation> replacements = new ArrayList<>();
        if (pick.impairedReservationId() == null) {
            for (StockItem item : stockItems.findHoldingRequest(report.requestId())) {
                item.activeReservationsFor(report.requestId()).forEach(r -> replacements.add(
                        new ReportPickDamageResult.Allocation(r.id().value(), item.location().code(),
                                item.lotNumber(), r.quantity().value())));
            }
        }
        List<UUID> after = new ArrayList<>();
        if (retained > 0) {
            after.add(holdId.value());
        }
        if (pick.impairedReservationId() != null) {
            after.add(pick.impairedReservationId());
        } else {
            replacements.forEach(r -> after.add(r.reservationId()));
        }
        return new ReportPickDamageResult(report.id(), report.number(), report.damageLocation().code(),
                report.quantity().value(), holdId.value(), retained, pick.impairedReservationId(), replacements,
                after, already);
    }

    private static List<UUID> holdsAfter(ReservationId hold, int retained, Reservation impaired,
                                         List<ReportPickDamageResult.Allocation> replacements) {
        List<UUID> after = new ArrayList<>();
        if (retained > 0) {
            after.add(hold.value());
        }
        if (impaired != null) {
            after.add(impaired.id().value());
        } else {
            replacements.forEach(r -> after.add(r.reservationId()));
        }
        return after;
    }

    private static List<StockDamageReport.Photo> photos(List<DamagePhoto> photos) {
        return photos.stream().map(p -> new StockDamageReport.Photo(Identifiers.newId(), p.storageKey(),
                p.originalName(), p.contentType(), p.sizeBytes(), p.checksum())).toList();
    }

    // ================================================================== decisions

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-damage-report", resourceId = "#reportId")
    public DamageReportView dispose(UUID reportId, DisposeDamageCommand command) {
        // The report's row first: two decisions of one report are taken one after the other.
        StockDamageReport report = reports.findByIdForUpdate(reportId).orElseThrow(() -> notFound(reportId));
        WarehouseScope.requireWarehouse(report.goods().warehouseId());
        if (report.dispositionFor(command.requestId()).isPresent()) {
            return view(report);
        }
        if (command.disposition() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A decision names what happens to the units");
        }
        Quantity units = Quantity.of(command.quantity());
        report.requireDecidable(command.disposition(), units, command.note(), command.decidedBy());
        policies.lock(report.goods().sku().code());
        Instant now = clock.instant();
        switch (command.disposition()) {
            case WRITE_OFF -> writeOff(report, command, units, now);
            case RETURN_TO_SUPPLIER -> returnToSupplier(report, command, units, now);
            case RESTORE -> restore(report, command, units, now);
        }
        StockDamageReport saved = reports.save(report);
        events.publishEventsOf(report);
        return view(saved);
    }

    /**
     * Destroyed: a DAMAGED adjustment of exactly these units, asked in the reporter's name and approved and posted
     * by the decider in one step - the four eyes the adjustment itself requires are the report's own - with the
     * destruction record numbered on the decision.
     */
    private void writeOff(StockDamageReport report, DisposeDamageCommand command, Quantity units, Instant now) {
        StockItem damaged = lockedDamagedRow(report);
        damaged.requireUnheldDamaged(units);
        LocalDate day = BusinessCalendar.date(now);
        String record = reports.nextWriteOffNumber(day);
        StockAdjustment adjustment = StockAdjustment.request(adjustments.nextNumber(day), damaged.location(),
                        damaged.sku(), damaged.lotNumber(), -units.value(), AdjustmentReason.DAMAGED,
                        clip("Damage report %s, destruction record %s%s".formatted(report.number(), record,
                                command.note() == null || command.note().isBlank() ? "" : ": " + command.note().trim())),
                        report.reportedBy(), now)
                .atLayer(damaged.receivedAt());
        adjustment.approveAndPost(command.decidedBy(), damaged, now);
        stockItems.save(damaged);
        adjustments.save(adjustment);
        report.writeOff(command.requestId(), units, command.note(), command.decidedBy(), record, adjustment.id(), now);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT, damaged.sku(),
                damaged.lotNumber(), damaged.location(), null, units.value(), StockStatus.DAMAGED,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, adjustment.id(),
                clip("DAMAGED: %s %s".formatted(report.number(), record)), command.decidedBy(), now)
                .ofLot(damaged.lotId()));
        log.info("Damage report {}: {} x {} written off at {} (adjustment {}, record {})", report.number(), units,
                damaged.sku(), damaged.location(), adjustment.number(), record);
    }

    /**
     * Going back to the supplier: only the decision is recorded. The units stay DAMAGED where they are, out of sale,
     * until procurement's return flow ships them - it listens to {@code StockDamageDisposed}; inventory cannot call
     * procurement (procurement depends on inventory).
     */
    private void returnToSupplier(StockDamageReport report, DisposeDamageCommand command, Quantity units,
                                  Instant now) {
        lockedDamagedRow(report).requireUnheldDamaged(units);
        Optional<StockLots.LotSupplier> supplier = lots.supplierOf(report.goods().lotId());
        report.returnToSupplier(command.requestId(), units, command.note(), command.decidedBy(),
                supplier.map(StockLots.LotSupplier::supplierId).orElse(null),
                supplier.map(StockLots.LotSupplier::supplierName).orElse(null), now);
        log.info("Damage report {}: {} x {} to go back to supplier {}", report.number(), units, report.goods().sku(),
                supplier.map(StockLots.LotSupplier::supplierName).orElse("(unknown: not on the lot)"));
    }

    /** Found fine on re-inspection: back onto an active bin of the same warehouse, AVAILABLE, into their own layer. */
    private void restore(StockDamageReport report, DisposeDamageCommand command, Quantity units, Instant now) {
        if (command.restoreLocationCode() == null || command.restoreLocationCode().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A restore names the bin the goods go back onto");
        }
        LocationId to = new LocationId(command.restoreLocationCode());
        WarehouseScope.requireLocation(to.code());
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        if (!locations.isBin(to) || !report.goods().warehouseId().equals(locations.warehouseOf(to).orElse(null))) {
            throw new BusinessException(ErrorCode.DAMAGE_RESTORE_LOCATION_INVALID,
                    "%s is not a bin of the warehouse damage report %s belongs to".formatted(to, report.number()));
        }
        StockItem damagedView = stockItems.findById(report.damagedStockItem()).orElseThrow();
        if (ExpiryPolicy.isExpired(damagedView.expiryDate(), BusinessCalendar.date(now))) {
            throw new BusinessException(ErrorCode.STOCK_EXPIRED, ("%s%s expired on %s; it is written off, not put "
                    + "back on sale").formatted(damagedView.sku(), lotSuffix(damagedView.lotNumber()),
                    damagedView.expiryDate()));
        }
        Optional<StockItem> layerThere = layerAt(damagedView, to);
        if (layerThere.isPresent() && (layerThere.get().status() != StockStatus.AVAILABLE
                || !layerThere.get().isSameGoodsAs(damagedView))) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH, ("%s holds these goods as %s; restore them to "
                    + "another bin").formatted(to, layerThere.get().status()));
        }
        TreeMap<String, StockItem> locked = lock(damagedView.id(), layerThere.map(StockItem::id).orElse(null),
                List.of());
        StockItem damaged = locked.get(damagedView.id().value().toString());
        damaged.giveUpRestored(units);
        StockItem restored;
        stockItems.saveNow(damaged);
        if (layerThere.isPresent()) {
            restored = locked.get(layerThere.get().id().value().toString());
            restored.moveIn(units);
            stockItems.saveMerged(restored);
        } else {
            restored = StockItem.arrivedAs(damaged, to, units, StockStatus.AVAILABLE);
            stockItems.save(restored);
        }
        report.restore(command.requestId(), units, command.note(), command.decidedBy(), to, restored.id(), now);
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, damaged.sku(),
                damaged.lotNumber(), damaged.location(), to, units.value(), StockStatus.DAMAGED, StockStatus.AVAILABLE,
                StockMovement.ReferenceType.DAMAGE_REPORT, report.id(), clip("RESTORE: " + command.note().trim()),
                command.decidedBy(), now).ofLot(damaged.lotId()));
        log.info("Damage report {}: {} x {} restored from {} to {}", report.number(), units, damaged.sku(),
                damaged.location(), to);
    }

    private StockItem lockedDamagedRow(StockDamageReport report) {
        return stockItems.findByIdForUpdate(report.damagedStockItem()).orElseThrow(() -> new BusinessException(
                ErrorCode.CONFLICT, "The damaged stock of report %s no longer exists".formatted(report.number())));
    }

    // ================================================================== reads

    @Override
    @Transactional(readOnly = true)
    public DamageReportView find(UUID reportId) {
        return view(inScope(reportId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DamageReportRow> list(UUID warehouseId, Boolean open, String sku, String origin, int page,
                                              int size, String sort) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        StockDamageReport.Origin from = origin == null || origin.isBlank() ? null : parseOrigin(origin);
        return Pages.toResponse(search.search(new StockDamageReportSearch.Criteria(warehouseId, open, sku, from),
                        Pages.of(page, size, SORT.parse(sort)))
                .map(row -> new DamageReportRow(row.id(), row.number(), row.origin().name(),
                        row.decidedQuantity() < row.quantity() ? "OPEN" : "CLOSED", row.warehouseId(), row.sku(),
                        row.lotNumber(), row.sourceLocationCode(), row.damageLocationCode(), row.quantity(),
                        row.decidedQuantity(), row.quantity() - row.decidedQuantity(), row.reason().name(),
                        row.orderId(), row.reportedBy(), row.reportedAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadLink photoLink(UUID reportId, UUID photoId) {
        StockDamageReport report = inScope(reportId);
        StockDamageReport.Photo photo = report.photos().stream().filter(p -> p.id().equals(photoId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Damage report %s has no photo %s".formatted(report.number(), photoId)));
        return evidence.link(photo.storageKey());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> damageAreas(UUID warehouseId) {
        WarehouseScope.requireWarehouse(warehouseId);
        return locations.quarantineAreas(warehouseId).stream().map(LocationId::code).toList();
    }

    /** Out of the caller's warehouses reads as not found: its existence is not theirs to know. */
    private StockDamageReport inScope(UUID reportId) {
        return reports.findById(reportId)
                .filter(report -> WarehouseScope.restriction()
                        .map(allowed -> allowed.ids().contains(report.goods().warehouseId())).orElse(true))
                .orElseThrow(() -> notFound(reportId));
    }

    // ================================================================== helpers

    /** Where the damaged units go, and the DAMAGED row of these goods already there if any. */
    private record Destination(LocationId location, StockItemId existing) {
    }

    /**
     * The quarantine area the reporter named, or the first usable one of the warehouse, by code, that can take these
     * goods: one holding no row of this receipt layer, or holding it already as DAMAGED (they merge). A layer held
     * there as QUARANTINE or BLOCKED cannot take DAMAGED units (one row per layer and location).
     */
    private Destination destination(StockItem source, UUID warehouseId, String requested) {
        if (requested != null && !requested.isBlank()) {
            LocationId to = new LocationId(requested);
            WarehouseScope.requireLocation(to.code());
            switch (locations.stateOf(to)) {
                case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
                case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                        "%s cannot take stock now: it or its warehouse is not active".formatted(to));
                case USABLE -> { }
            }
            if (!locations.isQuarantineArea(to) || to.equals(source.location())
                    || !warehouseId.equals(locations.warehouseOf(to).orElse(null))) {
                throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                        "Damaged goods go to another QUARANTINE area of the warehouse %s is in".formatted(
                                source.location()));
            }
            Optional<StockItem> there = layerAt(source, to);
            if (there.isPresent() && !mergesDamaged(there.get(), source)) {
                throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                        "%s holds these goods as %s; take the damaged ones to another quarantine area".formatted(to,
                                there.get().status()));
            }
            return new Destination(to, there.map(StockItem::id).orElse(null));
        }
        for (LocationId area : locations.quarantineAreas(warehouseId)) {
            if (area.equals(source.location())) {
                continue;
            }
            Optional<StockItem> there = layerAt(source, area);
            if (there.isEmpty() || mergesDamaged(there.get(), source)) {
                return new Destination(area, there.map(StockItem::id).orElse(null));
            }
        }
        throw new BusinessException(ErrorCode.DAMAGE_AREA_MISSING, ("Warehouse of %s has no usable QUARANTINE area "
                + "that can take %s%s as DAMAGED; add one on the warehouse map").formatted(source.location(),
                source.sku(), lotSuffix(source.lotNumber())));
    }

    private static boolean mergesDamaged(StockItem there, StockItem source) {
        return there.status() == StockStatus.DAMAGED && there.isSameGoodsAs(source);
    }

    /** The loose row of this receipt layer (lot, serial, receipt time) at {@code location}: the natural key's row. */
    private Optional<StockItem> layerAt(StockItem goods, LocationId location) {
        return stockItems.findLooseBySkuAndLocation(goods.sku(), location).stream()
                .filter(item -> item.isLayer(goods.lotNumber(), goods.serialNumber(), goods.receivedAt()))
                .findFirst();
    }

    /** Each row once, in id order: the order every stock writer locks in, so two of them never deadlock. */
    private TreeMap<String, StockItem> lock(StockItemId source, StockItemId destination,
                                            Collection<StockItemId> others) {
        TreeMap<String, StockItemId> ids = new TreeMap<>();
        ids.put(source.value().toString(), source);
        if (destination != null) {
            ids.put(destination.value().toString(), destination);
        }
        others.forEach(id -> ids.put(id.value().toString(), id));
        TreeMap<String, StockItem> locked = new TreeMap<>();
        for (var entry : ids.entrySet()) {
            locked.put(entry.getKey(), stockItems.findByIdForUpdate(entry.getValue()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-report".formatted(entry.getValue()))));
        }
        return locked;
    }

    /**
     * The source first, written at once - a serial number must leave its old row before its new one is inserted
     * ({@code uk_stock_item_live_serial}) - then the damaged row: merged into its own layer, or new.
     */
    private void saveMoved(StockItem source, StockItem damaged, boolean merged) {
        stockItems.saveNow(source);
        if (merged) {
            stockItems.saveMerged(damaged);
        } else {
            stockItems.save(damaged);
        }
    }

    private static StockMovement movedToQuarantine(StockDamageReport report, StockItem source, StockItem damaged,
                                                   Instant now) {
        return new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE, source.sku(),
                source.lotNumber(), source.location(), damaged.location(), report.quantity().value(),
                StockStatus.AVAILABLE, StockStatus.DAMAGED, StockMovement.ReferenceType.DAMAGE_REPORT, report.id(),
                clip(report.reason().name() + (report.note() == null ? "" : ": " + report.note())),
                report.reportedBy(), now).ofLot(source.lotId()).leaving(source.stockPackageId());
    }

    /**
     * What the SKU's live checkout reservations now promise beyond its free sellable stock. A reservation names no
     * units, so damage cannot release it; it can only leave it uncovered, and the release will then fall short.
     */
    private int shortfall(Sku sku, Instant now) {
        LocalDate today = BusinessCalendar.date(now);
        long free = stockItems.availableQuantities(Set.of(sku.code()), today).getOrDefault(sku.code(), 0L)
                - cycleCounts.frozen(List.of(sku.code()), today).stream().mapToLong(CycleCountRepository.Frozen::free).sum();
        long promised = skuReservations.heldBySku(List.of(sku.code())).getOrDefault(sku.code(), 0L);
        int shortfall = (int) Math.max(0, promised - Math.max(0, free));
        if (shortfall > 0) {
            log.warn("After damage, the checkout reservations of {} promise {} unit(s) beyond its free stock", sku,
                    shortfall);
        }
        return shortfall;
    }

    private UUID warehouseOf(LocationId location) {
        return locations.warehouseOf(location).orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                "No location " + location));
    }

    private StockItem holder(ReservationId holdId) {
        return stockItems.findIdByReservationId(holdId).flatMap(stockItems::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No allocation with id " + holdId.value()));
    }

    /** Same request, same row, same id: a replay finds what the first attempt made. */
    private static UUID derived(String purpose, UUID requestId, StockItemId row) {
        return UUID.nameUUIDFromBytes((purpose + ":" + requestId + ":" + row.value())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static StockDamageReport.Origin parseOrigin(String origin) {
        try {
            return StockDamageReport.Origin.valueOf(origin.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "origin is STOCK_CHECK or PICKING");
        }
    }

    private static BusinessException notFound(UUID reportId) {
        return new BusinessException(ErrorCode.DAMAGE_REPORT_NOT_FOUND, "No damage report " + reportId);
    }

    private static String clip(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    private static String lotSuffix(String lot) {
        return lot == null || lot.isBlank() ? "" : " lot " + lot;
    }

    private static DamageReportView view(StockDamageReport report) {
        StockDamageReport.DamagedGoods goods = report.goods();
        StockDamageReport.PickContext pick = report.pick();
        return new DamageReportView(report.id(), report.number(), report.origin().name(),
                report.isOpen() ? "OPEN" : "CLOSED", goods.warehouseId(), goods.sku().code(), goods.lotNumber(),
                goods.serialNumber(), goods.receivedAt(), goods.sourceStockItem().value(),
                goods.sourceLocation().code(),
                goods.sourceStockPackage() == null ? null : goods.sourceStockPackage().value(),
                report.damagedStockItem().value(), report.damageLocation().code(), report.quantity().value(),
                report.decided().value(), report.pending().value(), report.reason().name(), report.note(),
                pick == null ? null : pick.orderId(), pick == null ? null : pick.pickLineId(),
                pick == null ? null : pick.reservationId(), pick == null ? null : pick.impairedReservationId(),
                report.reportedBy(), report.reportedAt(),
                report.photos().stream().map(p -> new PhotoView(p.id(), p.originalName(), p.contentType(),
                        p.sizeBytes())).toList(),
                report.dispositions().stream().sorted(Comparator.comparing(StockDamageReport.Disposition::decidedAt))
                        .map(d -> new DispositionView(d.id(), d.type().name(), d.quantity().value(), d.note(),
                                d.recordNumber(), d.adjustmentId(),
                                d.restoreLocation() == null ? null : d.restoreLocation().code(), d.supplierId(),
                                d.supplierName(), d.decidedBy(), d.decidedAt()))
                        .toList());
    }
}
