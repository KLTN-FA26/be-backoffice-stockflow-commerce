package com.stockflow.inventory.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.ReallocateStockCommand;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * The only implementation of {@link InventoryService}, and the module's transaction boundary.
 *
 * <p><b>What the application layer is for.</b> It orchestrates: load the aggregates, call the
 * domain method, save, publish. It contains no business rules — every "may I?" and "how much?"
 * question is answered by {@link StockItem} or {@link StockAllocator}. If a rule appears in this
 * class it is in the wrong place, and {@code ArchitectureTest} is what keeps that honest.</p>
 *
 * <p><b>Package-private class, public interface.</b> Spring injects it by the interface. Nothing
 * outside this package can name the implementation, so nobody can bypass the port by
 * autowiring the concrete class and calling a method the interface does not expose.</p>
 *
 * <p><b>Clock is injected.</b> Every hold, allocation and ledger line is stamped with the time it was made, and
 * {@code Instant.now()} scattered through the code would make those stamps untestable.</p>
 */
@Service
@Transactional
class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);

    private final StockItemRepository repository;
    private final InventoryEventPublisher events;
    private final StockOperations operations;
    private final InventoryItemDirectory items;
    private final Clock clock;
    private final InventoryPolicyRepository policies;
    private final SkuReservationRepository skuReservations;
    private final LocationDirectory locations;
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts;
    private final com.stockflow.inventory.internal.domain.StockPackageRepository stockPackages;

    InventoryServiceImpl(StockItemRepository repository, InventoryEventPublisher events,
                         StockOperations operations, InventoryItemDirectory items, Clock clock,
                         InventoryPolicyRepository policies,
                         SkuReservationRepository skuReservations, LocationDirectory locations,
                         com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts,
                         com.stockflow.inventory.internal.domain.StockPackageRepository stockPackages) {
        this.stockPackages = stockPackages;
        this.repository = repository;
        this.events = events;
        this.operations = operations;
        this.items = items;
        this.clock = clock;
        this.policies = policies;
        this.skuReservations = skuReservations;
        this.locations = locations;
        this.cycleCounts = cycleCounts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareReservation(Set<Sku> skus) {
        // SKU advisory locks only (SCRUM-152): a SKU reservation touches no stock row, so locking stock rows here
        // would only serialise holding against picking.
        skus.stream().map(Sku::code).distinct().sorted().forEach(policies::lock);
    }

    /** Delegated: receipts, moves and adjustments write the ledger, which {@link StockOperations} owns. */
    @Override
    public StockMove receive(ReceiveStockCommand command) {
        return operations.receive(command);
    }

    @Override
    public List<StockPackageView> countReceivingPackages(CountReceivingPackagesCommand command) {
        return operations.countReceivingPackages(command);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<StockPackageView>> receivingPackagesOf(Collection<UUID> receiptLineIds) {
        return operations.receivingPackagesOf(receiptLineIds);
    }

    @Override
    public List<StockPackageView> moveStockPackages(MoveStockPackagesCommand command) {
        return operations.moveStockPackages(command);
    }

    @Override
    public List<StockPackageView> reclassifyStockPackages(ReclassifyStockPackagesCommand command) {
        return operations.reclassifyStockPackages(command);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryItemPolicy> itemPolicy(UUID inventoryItemId) {
        return items.policyOf(inventoryItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InventoryItemPolicy> itemPolicy(Sku sku) {
        return items.policyOf(sku.code());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, InventoryItemPolicy> itemPolicies(java.util.Collection<UUID> inventoryItemIds) {
        return items.policiesOf(inventoryItemIds);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> skusOf(java.util.Collection<UUID> inventoryItemIds) {
        return items.skusOf(inventoryItemIds);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, UUID> itemIdsOf(java.util.Collection<String> skus) {
        return items.itemIdsOf(skus);
    }

    @Override
    public void deleteUnusedItems(java.util.Collection<String> skus) {
        policies.deleteItems(skus);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<String> skusInUse(java.util.Collection<String> skus) {
        return policies.skusInUse(skus);
    }

    @Override
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        return operations.requestAdjustment(command);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> receiptLinesWithStockPackages(java.util.Collection<UUID> receiptLineIds) {
        return stockPackages.findByReceiptLines(receiptLineIds).stream()
                .map(com.stockflow.inventory.internal.domain.StockPackage::goodsReceiptLineId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Locked like every carton write (V20261020000100), though a carton being received holds no stock row to lock
     * after it: the receipt's own lock keeps two edits of it apart, this keeps the cartons' rule in one place.
     */
    @Override
    public void voidStockPackagesOfReceiptLines(java.util.Collection<UUID> receiptLineIds) {
        Instant now = clock.instant();
        var ids = stockPackages.findByReceiptLines(receiptLineIds).stream()
                .map(com.stockflow.inventory.internal.domain.StockPackage::id).toList();
        for (var carton : stockPackages.findAllByIdForUpdate(ids)) {
            if (carton.status() == com.stockflow.inventory.internal.domain.StockPackageStatus.RECEIVING) {
                carton.cancel(now);
                stockPackages.save(carton);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public int availableToPromise(Sku sku) {
        return Math.toIntExact(
                availableQuantities(Set.of(sku.code())).getOrDefault(sku.code(), 0L));
    }

    /** Unheld sellable units minus live SKU reservations, per SKU, never below zero; absent keys mean zero. */
    private Map<String, Long> netOfSkuReservations(Map<String, Long> unheld, Set<String> skus) {
        Map<String, Long> reserved = skuReservations.heldBySku(skus);
        Map<String, Long> net = new java.util.HashMap<>();
        for (String sku : skus) {
            long value = unheld.getOrDefault(sku, 0L) - reserved.getOrDefault(sku, 0L);
            if (value > 0) {
                net.put(sku, value);
            }
        }
        return net;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> availableQuantities(Set<String> skus) {
        var normalized =
                skus.stream()
                .map(code -> new Sku(code).code())
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) return Map.of();
        return netOfSkuReservations(allocatableBySku(normalized), normalized);
    }

    /**
     * Units a release could allocate, per SKU code (team plan 4C-2; kltn-docs 07 BR-03, 20 §5.6): whole sealed cartons
     * nothing holds, unexpired, on pickable bins (module 06 BR-12), none a recount has frozen (X-SHORT step 03). The
     * same stock {@link PickableStock} offers the planner, across every warehouse: a reservation is promised against
     * the whole system. Loose units and an opened carton are on hand but never allocated, so they are not promised.
     */
    private Map<String, Long> allocatableBySku(Collection<String> skus) {
        return new PickableStock(repository, locations, cycleCounts).allocatableBySku(skus);
    }

    private Set<StockItemId> frozenLayers(Sku sku) {
        return cycleCounts.frozen(List.of(sku.code())).stream()
                .map(frozen -> new StockItemId(frozen.stockItemId())).collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Sku, Integer> availableToPromise(Collection<Sku> skus) {
        Set<Sku> wanted = new LinkedHashSet<>(skus);
        List<String> codes = wanted.stream().map(Sku::code).toList();
        Map<String, Long> found = allocatableBySku(codes);
        Map<String, Long> reserved = skuReservations.heldBySku(codes);
        Map<Sku, Integer> result = new LinkedHashMap<>();
        // Every SKU asked for gets an answer: no stock is an answer (0), not a missing key.
        wanted.forEach(sku -> result.put(sku, (int) Math.max(0, found.getOrDefault(sku.code(), 0L)
                - reserved.getOrDefault(sku.code(), 0L))));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public int availableToPromise(Sku sku, String warehouseCode) {
        String wanted = new LocationId(warehouseCode).code();
        return levelsOf(sku).stream()
                .filter(level -> level.warehouseCode().equals(wanted))
                .mapToInt(StockLevel::atp)
                .sum();     // levelsOf already caps each warehouse at the system-wide figure
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockAvailability> availabilityOf(Sku sku) {
        Set<StockItemId> frozen = frozenLayers(sku);
        // A staging area's cartons are an order's, or wait to be put back: nobody takes stock from there (20 BR-20).
        Set<String> staging = locations.stagingAreas();
        // In picking order: FIFO by receipt time (kltn-docs 01 BR-17).
        return repository.findAvailableBySku(sku).stream()
                .filter(item -> !frozen.contains(item.id()))
                .filter(item -> !staging.contains(item.location().code()))
                .sorted(Comparator.comparing(item ->
                                        new StockAllocator.Candidate(
                                                item.id(),
                                                item.location(),
                                                item.lotNumber(),
                                                item.status(),
                                                item.available(),
                                                item.receivedAt()),
                                StockAllocator.comparator()))
                .map(this::toAvailability)
                // BR-SEC-002: a warehouse-bound caller sees the stock of their own warehouses only.
                .filter(row -> com.stockflow.common.security.WarehouseScope.restriction()
                        .map(allowed -> allowed.prefixes().contains(
                                com.stockflow.common.security.WarehouseScope.prefixOf(row.locationCode())))
                        .orElse(true))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLevel> levelsOf(Sku sku) {
        return levels(sku, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> availableToPromiseByWarehouse(Sku sku) {
        Map<String, Integer> byWarehouse = new TreeMap<>();
        levels(sku, false).forEach(level -> byWarehouse.put(level.warehouseCode(), level.atp()));
        return byWarehouse;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The hold is checked against the warehouse's figure read under the SKU's advisory lock - the same lock every
     * allocation and other hold of the SKU takes - and again by {@code tg_stock_reservation_soft_atp} per
     * warehouse. Whole cartons only: the figure is cut down to a multiple of the carton (17 BR-21).</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public com.stockflow.inventory.api.HoldStockResult holdAtWarehouse(
            com.stockflow.inventory.api.HoldStockCommand command) {
        String warehouse = new LocationId(command.warehouseCode()).code();
        List<SkuReservation> served = skuReservations.findHeldForRequest(command.requestId());
        if (!served.isEmpty()) {
            int units = served.stream().mapToInt(r -> r.quantity().value()).sum();
            return new com.stockflow.inventory.api.HoldStockResult(command.orderId(), command.sku().code(), warehouse,
                    units / command.unitsPerCarton(), served.stream().map(r -> r.id().value()).toList());
        }
        policies.lock(command.sku().code());   // re-entrant: the caller may have taken it in prepareReservation
        int available = availableToPromiseByWarehouse(command.sku()).getOrDefault(warehouse, 0);
        int cartons = Math.min(command.cartons(), available / command.unitsPerCarton());
        if (cartons == 0) {
            log.info("Order {}: no whole carton of {} available at {} to hold", command.orderId(), command.sku(),
                    warehouse);
            return new com.stockflow.inventory.api.HoldStockResult(command.orderId(), command.sku().code(), warehouse,
                    0, List.of());
        }
        SkuReservation reservation = SkuReservation.holdAt(warehouse, command.sku(), command.orderId(),
                command.requestId(), Quantity.of(cartons * command.unitsPerCarton()), available, clock.instant());
        skuReservations.save(reservation);
        events.publishEventsOf(reservation);
        log.info("Order {}: {} of {} carton(s) of {} held at {} (SKU reservation {})", command.orderId(), cartons,
                command.cartons(), command.sku(), warehouse, reservation.id());
        return new com.stockflow.inventory.api.HoldStockResult(command.orderId(), command.sku().code(), warehouse,
                cartons, List.of(reservation.id().value()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The holds are read without a row lock; the caller takes the SKU's advisory lock first when it acts on them, as
     * every hold and allocation of the SKU does.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public com.stockflow.inventory.api.SkuHoldCover skuHoldCover(Sku sku, String warehouseCode) {
        String warehouse = new LocationId(warehouseCode).code();
        long coverable = new PickableStock(repository, locations, cycleCounts).coverableAt(sku, warehouse);
        List<com.stockflow.inventory.api.SkuHoldCover.Hold> holds = skuReservations.findHeldAt(sku.code(), warehouse)
                .stream().map(r -> new com.stockflow.inventory.api.SkuHoldCover.Hold(r.id().value(), r.orderId(),
                        r.quantity().value(), r.reservedAt()))
                .toList();
        return new com.stockflow.inventory.api.SkuHoldCover(sku.code(), warehouse, Math.toIntExact(coverable), holds);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks the SKU, then the hold. The replacement's request id is named after the hold and the size kept, so a
     * repeated call finds it instead of holding twice ({@code uk_stock_reservation_request}).</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<UUID> shrinkHold(UUID reservationId, int keepUnits) {
        SkuReservation found = skuReservations.findById(ReservationId.of(reservationId))
                .filter(r -> r.warehouseCode() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No hold at a warehouse with id " + reservationId));
        if (keepUnits < 0 || keepUnits >= found.quantity().value()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Hold %s of %d unit(s) cannot keep %d"
                    .formatted(reservationId, found.quantity().value(), keepUnits));
        }
        UUID requestId = UUID.nameUUIDFromBytes(("shrink:%s:%d".formatted(reservationId, keepUnits))
                .getBytes(StandardCharsets.UTF_8));
        policies.lock(found.sku().code());   // re-entrant: the caller may have taken it in prepareReservation
        List<SkuReservation> replayed = skuReservations.findHeldForRequest(requestId);
        if (!replayed.isEmpty()) {
            return Optional.of(replayed.getFirst().id().value());
        }
        SkuReservation hold = skuReservations.findByIdForUpdate(ReservationId.of(reservationId)).orElseThrow();
        Instant now = clock.instant();
        if (!hold.release(ReleaseReason.SHORT_PICKED, now)) {
            if (keepUnits == 0 && hold.status() == ReservationStatus.RELEASED
                    && hold.releaseReason() == ReleaseReason.SHORT_PICKED) {
                return Optional.empty();
            }
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Hold %s is already %s".formatted(reservationId, hold.status()));
        }
        skuReservations.save(hold);
        events.publishEventsOf(hold);
        if (keepUnits == 0) {
            log.info("Order {}: hold {} of {} at {} given up whole; stock no longer covers it", hold.orderId(),
                    reservationId, hold.sku(), hold.warehouseCode());
            return Optional.empty();
        }
        // The release first: tg_stock_reservation_soft_atp checks the insert against every hold still HELD.
        skuReservations.flush();
        SkuReservation kept = skuReservations.save(hold.keeping(keepUnits, requestId, now));
        events.publishEventsOf(kept);
        log.info("Order {}: hold {} of {} at {} cut from {} to {} unit(s) (hold {}); stock no longer covers it",
                hold.orderId(), reservationId, hold.sku(), hold.warehouseCode(), hold.quantity(), keepUnits, kept.id());
        return Optional.of(kept.id().value());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public Set<UUID> holdsGivenUpWithTheirCarton(Collection<UUID> reservationIds) {
        if (reservationIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> givenUp = new LinkedHashSet<>();
        for (com.stockflow.inventory.internal.domain.HoldDetail detail : repository.findHolds(Set.copyOf(reservationIds))) {
            if (detail.status() != ReservationStatus.RELEASED) {
                continue;
            }
            ReservationId id = ReservationId.of(detail.reservationId());
            repository.findIdByReservationId(id).flatMap(repository::findById)
                    .filter(row -> row.findReservation(id).map(hold -> givenUpWithItsCarton(hold, row)).orElse(false))
                    .ifPresent(row -> givenUp.add(detail.reservationId()));
        }
        return givenUp;
    }

    /**
     * Each warehouse's levels of the SKU. ATP per warehouse (20 BR-01): what a release there could allocate - whole
     * free cartons on pickable, usable locations, none frozen, the very set the release plans over
     * ({@link PickableStock}; review P-1: a carton in a receiving area is on hand, never allocated, so never promised) -
     * less the SKU reservations held at it (4B-7, BR-17), never more than is left system-wide once every SKU
     * reservation - held at a warehouse or not - is counted. On hand, available and reserved are the rows' figures, as
     * Inventory Level shows them. {@code scoped}: only the warehouses a warehouse-bound caller may see.
     */
    private List<StockLevel> levels(Sku sku, boolean scoped) {
        record Totals(int onHand, int available, int reserved, int atp) {
            static Totals of(StockLevelLine line) {
                int sellable = line.status() == StockStatus.AVAILABLE ? line.onHand() : 0;
                return new Totals(line.onHand(), sellable, line.reserved(), 0);
            }

            Totals plus(Totals other) {
                return new Totals(onHand + other.onHand, available + other.available,
                        reserved + other.reserved,
                        atp + other.atp);
            }
        }
        Map<String, Totals> byWarehouse = new TreeMap<>();
        for (StockLevelLine line : repository.findLevelLinesBySku(sku)) {
            byWarehouse.merge(line.location().warehouseCode(), Totals.of(line), Totals::plus);
        }
        Map<String, Long> allocatable = new PickableStock(repository, locations, cycleCounts).allocatableByWarehouse(sku);
        byWarehouse.replaceAll((code, totals) -> new Totals(totals.onHand(), totals.available(), totals.reserved(),
                Math.toIntExact(allocatable.getOrDefault(code, 0L))));
        // Allocations are holds on these same rows: counted in reserved above, shown apart below (glossary: a
        // reservation is a soft hold on the SKU, an allocation a hard hold on a bin), so the two never add up twice.
        Map<String, Integer> allocatedByWarehouse = new TreeMap<>();
        repository.findAllocatedBySku(sku).forEach((locationCode, units) ->
                allocatedByWarehouse.merge(new LocationId(locationCode).warehouseCode(), units, Integer::sum));
        // A SKU reservation of before 4B-7 named no warehouse and was promised against the whole system (SCRUM-152):
        // no warehouse can promise more than is left system-wide once every SKU reservation is counted - from these
        // same lines and this same day.
        long unheld = byWarehouse.values().stream().mapToLong(Totals::atp).sum();
        int systemWide = (int) Math.max(0, unheld - skuReservations.heldBySku(List.of(sku.code()))
                .getOrDefault(sku.code(), 0L));
        // 4B-7 (20 BR-17): what an order of a contract holds at a warehouse is promised from that warehouse.
        skuReservations.heldBySkuAt(sku.code()).forEach((code, units) -> byWarehouse.computeIfPresent(code,
                (key, totals) -> new Totals(totals.onHand(), totals.available(), totals.reserved(),
                        (int) Math.max(0, totals.atp() - units))));
        return byWarehouse.entrySet().stream()
                // BR-SEC-002: a warehouse-bound caller sees their own warehouses' levels only.
                .filter(entry -> !scoped || com.stockflow.common.security.WarehouseScope.restriction()
                        .map(allowed -> allowed.prefixes().contains(entry.getKey()))
                        .orElse(true))
                .map(entry -> {
                    Totals totals = entry.getValue();
                    int allocated = Math.min(allocatedByWarehouse.getOrDefault(entry.getKey(), 0), totals.reserved());
                    return new StockLevel(sku.code(), entry.getKey(),
                            totals.onHand(), totals.available(),
                            totals.reserved() - allocated, allocated,
                            Math.min(totals.atp(), systemWide));
                })
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The order of locks is the one every hold and move uses: the SKUs' advisory locks (sorted), then the
     * order's reservations, then the stock rows in id order. Planning reads projections only, so the locked read of
     * each stock row is its first read in this transaction and the re-check in {@link StockItem#allocate} runs on
     * current numbers. Each stock row is locked once even when two lines of the order draw on it — refreshing a
     * row twice would discard the first line's unflushed allocation.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public AllocateStockResult allocate(AllocateStockCommand command) {
        Instant now = clock.instant();
        String warehouse = new LocationId(command.warehouseCode()).code();
        List<SkuReservation> unlocked = skuReservations.findHeldForOrder(command.orderId()).stream()
                .filter(r -> command.includes(r.id().value())).toList();
        if (unlocked.isEmpty()) {
            requireNoneLapsed(command.orderId());
            return new AllocateStockResult(command.orderId(), warehouse, List.of());
        }
        unlocked.stream().map(r -> r.sku().code()).distinct().sorted().forEach(policies::lock);
        List<SkuReservation> held = skuReservations.findHeldForOrderForUpdate(command.orderId()).stream()
                .filter(r -> command.includes(r.id().value())).toList();
        // After the lock: a reservation released by hand a moment ago is seen as released here, not skipped.
        requireNoneLapsed(command.orderId());

        record Planned(SkuReservation from, int sequence, StockAllocator.AllocationLine line) { }
        List<Planned> planned = new ArrayList<>();
        Map<Sku, List<StockAllocator.Candidate>> pools = new LinkedHashMap<>();
        int sequence = 0;
        Map<Sku, Integer> needed = new LinkedHashMap<>();
        held.forEach(r -> needed.merge(r.sku(), r.quantity().value(), Integer::sum));
        for (Map.Entry<Sku, Integer> want : needed.entrySet()) {
            int ownHere = held.stream().filter(r -> r.sku().equals(want.getKey())
                    && warehouse.equals(r.warehouseCode())).mapToInt(r -> r.quantity().value()).sum();
            requireRoomBesideHoldsAt(want.getKey(), warehouse, want.getValue(), ownHere);
        }
        for (SkuReservation reservation : held) {
            if (reservation.warehouseCode() != null && !reservation.warehouseCode().equals(warehouse)) {
                // 4B-7 (20 BR-17): held at the order's shipping warehouse, allocated there or not at all.
                throw new BusinessException(ErrorCode.RESERVATION_WAREHOUSE_MISMATCH, ("Order %s holds %s at %s, not "
                        + "at %s").formatted(command.orderId(), reservation.sku(), reservation.warehouseCode(),
                        warehouse));
            }
            List<StockAllocator.Candidate> pool = pools.computeIfAbsent(reservation.sku(),
                    sku -> pickableCandidates(sku, warehouse, null));
            List<StockAllocator.AllocationLine> lines = plan(reservation.sku(), pool, reservation.quantity(),
                    warehouse);
            for (StockAllocator.AllocationLine line : lines) {
                planned.add(new Planned(reservation, sequence++, line));
            }
            pools.put(reservation.sku(), without(pool, lines));
        }

        Map<StockItemId, List<Planned>> byItem = new TreeMap<>(Comparator.comparing(id -> id.value().toString()));
        planned.forEach(p -> byItem.computeIfAbsent(p.line().stockItemId(), id -> new ArrayList<>()).add(p));
        Map<Integer, StockReservation> made = new TreeMap<>();
        for (Map.Entry<StockItemId, List<Planned>> entry : byItem.entrySet()) {
            StockItem locked = repository.findByIdForUpdate(entry.getKey()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-allocation".formatted(entry.getKey())));
            for (Planned p : entry.getValue()) {
                Reservation allocation = locked.allocate(p.from(), allocationRequestId(p.from().id(), entry.getKey()),
                        p.line().quantity(), now);
                made.put(p.sequence(), toApiReservation(locked, allocation));
            }
            repository.save(locked);
            events.publishEventsOf(locked);
        }

        List<AllocateStockResult.Allocation> allocations = new ArrayList<>();
        for (SkuReservation reservation : held) {
            reservation.markAllocated(now);
            skuReservations.save(reservation);
            allocations.add(new AllocateStockResult.Allocation(reservation.id().value(), reservation.sku().code(),
                    planned.stream().filter(p -> p.from() == reservation).map(p -> made.get(p.sequence())).toList()));
        }
        log.info("Allocated {} reservation(s) of order {} in warehouse {} to {} bin/lot hold(s)", held.size(),
                command.orderId(), warehouse, made.size());
        return new AllocateStockResult(command.orderId(), warehouse, allocations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks: the SKU, then the short allocation's row and the new rows together, in id order. The new
     * allocations remember the SKU reservation the short one came from, so the lot trace and the order keep one
     * line of descent.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public List<StockReservation> reallocate(ReallocateStockCommand command) {
        Instant now = clock.instant();
        ReservationId shortId = ReservationId.of(command.reservationId());
        StockItem view = repository.findIdByReservationId(shortId).flatMap(repository::findById)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No allocation with id " + command.reservationId()));
        Reservation shortHold = view.findReservation(shortId).orElseThrow();
        if (!command.orderId().equals(shortHold.orderId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                    "Allocation %s is not order %s's".formatted(command.reservationId(), command.orderId()));
        }
        List<StockReservation> replayed = repository.findWithReservationsForRequest(command.requestId()).stream()
                .flatMap(item -> item.activeReservationsFor(command.requestId()).stream()
                        .map(r -> toApiReservation(item, r)))
                .toList();
        if (!replayed.isEmpty()) {
            return replayed;
        }
        policies.lock(view.sku().code());
        // Released and reallocated to whole cartons, or INSUFFICIENT_STOCK leaves everything as it was (4C-4 B1).
        String warehouse = view.location().warehouseCode();
        // 4B-7: the new bins come out of what other orders do not hold at this warehouse by SKU (20 BR-17). One pool
        // for both: every pickable carton but the short one's (4C-2 excludes a stock row, no longer a whole bin).
        List<StockAllocator.Candidate> pool = pickableCandidates(view.sku(), warehouse, view.id());
        requireRoomBesideHoldsAt(view.sku(), warehouse, shortHold.quantity().value(), 0, pool);
        List<StockAllocator.AllocationLine> lines = plan(view.sku(), pool, shortHold.quantity(), warehouse);
        UUID origin = shortHold.allocatedFrom() != null ? shortHold.allocatedFrom() : shortHold.id().value();

        Set<StockItemId> ids = new java.util.TreeSet<>(Comparator.comparing(id -> id.value().toString()));
        ids.add(view.id());
        lines.forEach(line -> ids.add(line.stockItemId()));
        Map<StockItemId, StockReservation> made = new LinkedHashMap<>();
        for (StockItemId id : ids) {
            StockItem locked = repository.findByIdForUpdate(id).orElseThrow();
            if (id.equals(view.id())) {
                Reservation current = locked.findReservation(shortId).orElseThrow();
                if (current.status().isActive()) {
                    locked.releaseReservation(shortId, ReleaseReason.SHORT_PICKED, now);
                } else if (!givenUpWithItsCarton(current, locked)) {
                    throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                            "Allocation %s is already %s".formatted(shortId, current.status()));
                }
            }
            for (StockAllocator.AllocationLine line : lines) {
                if (line.stockItemId().equals(id)) {
                    Reservation allocation = locked.allocate(command.orderId(), origin, command.requestId(),
                            allocationRequestId(ReservationId.of(command.requestId()), id), line.quantity(), now);
                    made.put(id, toApiReservation(locked, allocation));
                }
            }
            repository.save(locked);
            events.publishEventsOf(locked);
        }
        log.info("Allocation {} of order {} came up short at {}; reallocated {} unit(s) to {} hold(s)", shortId,
                command.orderId(), view.location(), shortHold.quantity(), made.size());
        return lines.stream().map(line -> made.get(line.stockItemId())).toList();
    }

    /**
     * Whether the short allocation was already given up because its carton was written off: an approved count variance
     * released it ({@code SHORT_PICKED}) while its pick line waited for this reallocation (20 §4.2 step 8, 07 §4.4), and
     * the carton is empty now. A hold given up by an earlier reallocation names a carton still in stock, and is refused
     * as before; a replay of that reallocation never gets here (it is answered from its own holds).
     */
    private static boolean givenUpWithItsCarton(Reservation hold, StockItem row) {
        return hold.status() == ReservationStatus.RELEASED && hold.releaseReason() == ReleaseReason.SHORT_PICKED
                && row.onHand().isZero();
    }

    /**
     * Sellable stock of the SKU on pickable, usable locations of one warehouse (module 06 BR-12), never at
     * {@code excluded}; read as projections, so nothing lands in the persistence context before the locked reads.
     * The one definition: {@link PickableStock}.
     */
    private List<StockAllocator.Candidate> pickableCandidates(Sku sku, String warehouse, StockItemId excluded) {
        return new PickableStock(repository, locations, cycleCounts).candidates(sku, warehouse, excluded);
    }

    /**
     * All or nothing (docs 04c F-FUL): a line whose reservation was released before the release has no stock behind
     * it, and allocating the other lines would send the order to the warehouse short. Holds carry no deadline since
     * V20261117000300 (an expired row is one of before it), so this is the race with a cancellation or a release by
     * hand.
     *
     * <p>4B-7: holds at a warehouse (orders of a contract) are left out. They never expire, one released by hand
     * (20 §5.6, {@code MANUAL_OVERRIDE}) is replaced by the order holding again, and the order checks that every carton
     * is held before it is released ({@code ShippingWarehouseService.requireFullyHeld}).</p>
     */
    private void requireNoneLapsed(UUID orderId) {
        if (skuReservations.anyLapsedForOrder(orderId)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "A reservation of order %s expired or was released; the order cannot be allocated".formatted(orderId));
        }
    }

    /**
     * 4B-7 (20 BR-17): what is allocated at a warehouse leaves room for what other orders hold there by SKU - an order
     * of a contract's holds at its shipping warehouse are its stock, though no bin is chosen yet. {@code ownHere}: this
     * order's own holds at the warehouse among the {@code units} it allocates.
     *
     * @throws BusinessException {@code INSUFFICIENT_STOCK} naming the SKU and the warehouse
     */
    private void requireRoomBesideHoldsAt(Sku sku, String warehouse, int units, int ownHere) {
        requireRoomBesideHoldsAt(sku, warehouse, units, ownHere, pickableCandidates(sku, warehouse, null));
    }

    private void requireRoomBesideHoldsAt(Sku sku, String warehouse, int units, int ownHere,
                                          List<StockAllocator.Candidate> pool) {
        long others = skuReservations.heldBySkuAt(sku.code()).getOrDefault(warehouse, 0L) - ownHere;
        if (others <= 0) {
            return;
        }
        long pickable = pool.stream().mapToLong(c -> c.available().value()).sum();
        if (pickable - others < units) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, ("Warehouse %s has %d pickable unit(s) of %s, %d of "
                    + "them held for other orders; %d are needed").formatted(warehouse, pickable, sku, others, units));
        }
    }

    /**
     * Whole cartons through {@link StockAllocator}, FIFO; names the warehouse when short.
     * All or nothing for now: a warehouse with only k &lt; N cartons refuses (07 BR-03.4 holds the k and sends N − k to
     * a Production Order, but until the order can wait for them - {@code AWAITING_STOCK}, team plan 4A-6 - an order
     * released short would go to the floor with nothing tracking what it lacks).
     */
    private List<StockAllocator.AllocationLine> plan(Sku sku, List<StockAllocator.Candidate> pool, Quantity quantity,
                                                     String warehouse) {
        StockAllocator.Plan plan = StockAllocator.plan(sku.code(), pool, quantity);
        if (plan.isShort()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "Warehouse %s has whole cartons for %d pickable unit(s) of %s in %d carton(s); %d are needed"
                            .formatted(warehouse, plan.planned(), sku.code(), plan.lines().size(), quantity.value()));
        }
        return plan.lines();
    }

    /** The pool after {@code taken}: what a second line of the same SKU may still draw on. A carton goes whole. */
    private static List<StockAllocator.Candidate> without(List<StockAllocator.Candidate> pool,
                                                          List<StockAllocator.AllocationLine> taken) {
        Set<StockItemId> used = taken.stream().map(StockAllocator.AllocationLine::stockItemId)
                .collect(Collectors.toSet());
        return pool.stream().filter(c -> !used.contains(c.stockItemId())).toList();
    }

    /** Same reservation and stock item, same key: allocating again after a retry finds the first allocation. */
    private static UUID allocationRequestId(ReservationId from, StockItemId stockItemId) {
        return UUID.nameUUIDFromBytes(("allocation:" + from.value() + ":" + stockItemId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static StockReservation toApiReservation(StockItem item, Reservation reservation) {
        return new StockReservation(
                reservation.id().value(),
                item.id().value(),
                item.location().code(),
                item.lotNumber(),
                // The reservation's own quantity, never the plan's: on a replay the two can differ.
                reservation.quantity().value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderHold> holdsOf(UUID orderId) {
        List<OrderHold> holds = new ArrayList<>();
        skuReservations.findHeldForOrder(orderId)
                .forEach(r -> holds.add(new OrderHold(r.sku().code(), null, r.quantity().value())));
        repository.findHeldForOrder(orderId)
                .forEach(held -> holds.add(new OrderHold(held.sku(), held.locationCode(), held.quantity())));
        return holds;
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.stockflow.inventory.api.HoldView> holds(Collection<UUID> reservationIds) {
        List<com.stockflow.inventory.api.HoldView> views = new ArrayList<>(
                repository.findHolds(Set.copyOf(reservationIds)).stream()
                .map(hold -> new com.stockflow.inventory.api.HoldView(hold.reservationId(), hold.sku(),
                        hold.locationCode(), hold.lotNumber(), hold.quantity(),
                        com.stockflow.inventory.api.HoldView.Status.valueOf(hold.status().name()), hold.expiresAt(),
                        hold.lpn(), hold.wholeCarton()))
                .toList());
        skuReservations.findAll(Set.copyOf(reservationIds)).forEach(r -> views.add(
                new com.stockflow.inventory.api.HoldView(r.id().value(), r.sku().code(), null, null,
                        r.quantity().value(), com.stockflow.inventory.api.HoldView.Status.valueOf(r.status().name()),
                        r.expiresAt())));
        return views;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A SKU reservation is released as itself; a hold on a stock row frees its units on that row. A print line's
     * goods are released like any other: they are the customer's goods in stock, printed outside the system.</p>
     */
    @Override
    public void release(UUID reservationId, String reason) {
        ReleaseReason releaseReason = parseReason(reason);
        if (skuReservations.exists(ReservationId.of(reservationId))) {
            releaseSkuReservation(ReservationId.of(reservationId), releaseReason);
            return;
        }
        StockItem item = holderOf(ReservationId.of(reservationId));
        item.releaseReservation(ReservationId.of(reservationId), releaseReason, clock.instant());
        repository.save(item);
        events.publishEventsOf(item);

        log.info("Released reservation {} ({})", reservationId, releaseReason);
    }

    /**
     * A SKU reservation sits in no warehouse, so a warehouse-bound caller cannot reach one (BR-SEC-002): it is not
     * theirs to release. Once ALLOCATED its units live in allocations, which the order releases by their own ids;
     * releasing the reservation itself then changes nothing.
     */
    private void releaseSkuReservation(ReservationId id, ReleaseReason reason) {
        if (WarehouseScope.restriction().isPresent()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND, "No reservation with id " + id.value());
        }
        SkuReservation reservation = skuReservations.findByIdForUpdate(id).orElseThrow();
        if (reservation.release(reason, clock.instant())) {
            skuReservations.save(reservation);
            events.publishEventsOf(reservation);
            log.info("Released SKU reservation {} ({})", id, reason);
        } else {
            log.info("SKU reservation {} is already {}; nothing to release", id, reservation.status());
        }
    }

    /**
     * The stock item holding {@code reservationId}, locked. A warehouse-bound caller reaches only
     * holds in their own warehouses (BR-SEC-002); the system — an event listener — is not limited.
     */
    private StockItem holderOf(ReservationId reservationId) {
        StockItem item = repository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No reservation with id " + reservationId.value()));
        WarehouseScope.requireLocation(item.location().code());
        return item;
    }

    /**
     * Same inputs, same id, every time.
     *
     * <p>Type 3 (name-based) UUID semantics, derived from the caller's request id and the stock
     * item it applies to. Two properties matter: it differs per stock item, so the unique
     * constraint on {@code request_id} is not violated when one line spans several lots; and it is
     * stable across processes and restarts, so a retried request is recognised as a replay rather
     * than held a second time.</p>
     */
    private static UUID perStockItemRequestId(UUID requestId, StockItemId stockItemId) {
        return UUID.nameUUIDFromBytes((requestId + ":" + stockItemId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Free-text reason in, enum out. The string arrives from another module's call or from JSON,
     * so it is validated here at the boundary — nothing downstream ever sees an unparsed reason.
     */
    private ReleaseReason parseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return ReleaseReason.MANUAL_OVERRIDE;
        }
        try {
            return ReleaseReason.valueOf(reason.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown release reason '{}', recording as MANUAL_OVERRIDE", LogSafe.text(reason));
            return ReleaseReason.MANUAL_OVERRIDE;
        }
    }

    private StockAvailability toAvailability(StockItem item) {
        return new StockAvailability(
                item.id().value(),
                item.sku().code(),
                item.location().code(),
                item.lotNumber(),
                item.onHand().value(),
                item.reserved().value(),
                0,
                item.available().value());
    }
}
