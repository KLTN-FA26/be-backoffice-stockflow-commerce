package com.stockflow.inventory.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
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
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * <p><b>Clock is injected.</b> Reservation expiry is time-dependent, and
 * {@code Instant.now()} scattered through the code makes that untestable. With a {@code Clock}
 * bean the sweeper test fast-forwards 31 minutes instead of sleeping for them.</p>
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
    private final InventoryControlService controls;
    private final InventoryPolicyRepository policies;
    private final SkuReservationRepository skuReservations;
    private final LocationDirectory locations;
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts;
    private final com.stockflow.inventory.internal.domain.StockPackageRepository stockPackages;
    private final com.stockflow.inventory.internal.domain.StockDamageReportRepository damageReports;

    /** How long a hold lasts while its order waits for payment (kltn-docs 14: set by the business). */
    private final java.time.Duration reservationTtl;

    InventoryServiceImpl(StockItemRepository repository, InventoryEventPublisher events,
                         StockOperations operations, InventoryItemDirectory items, Clock clock,
                         InventoryControlService controls, InventoryPolicyRepository policies,
                         SkuReservationRepository skuReservations, LocationDirectory locations,
                         com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts,
                         com.stockflow.inventory.internal.domain.StockPackageRepository stockPackages,
                         com.stockflow.inventory.internal.domain.StockDamageReportRepository damageReports,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${stockflow.inventory.reservation-ttl:PT30M}") java.time.Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("stockflow.inventory.reservation-ttl must be positive");
        }
        this.stockPackages = stockPackages;
        this.damageReports = damageReports;
        this.reservationTtl = reservationTtl;
        this.repository = repository;
        this.events = events;
        this.operations = operations;
        this.items = items;
        this.clock = clock;
        this.controls = controls;
        this.policies = policies;
        this.skuReservations = skuReservations;
        this.locations = locations;
        this.cycleCounts = cycleCounts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LowStockItem> lowStockBatch(String afterSku, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<LowStockItem> low = new ArrayList<>();
        String cursor = afterSku == null ? "" : afterSku;
        while (low.size() < limit) {
            // A scan page is every SKU with a threshold, low or not, so keep reading until enough are low.
            var page = policies.thresholdPage(cursor, Math.max(limit, 200), today);
            for (var row : page) {
                if (StockThresholds.isLow(row.usableOnHand(), row.reorderPoint(), row.safetyStock())) {
                    low.add(new LowStockItem(row.sku(), row.usableOnHand(), row.reorderPoint(), row.safetyStock(),
                            Boolean.TRUE.equals(StockThresholds.reorderRequired(row.usableOnHand(), row.reorderPoint())),
                            Boolean.TRUE.equals(StockThresholds.belowSafetyStock(row.usableOnHand(), row.safetyStock()))));
                    if (low.size() == limit) {
                        return low;
                    }
                }
                cursor = row.sku();
            }
            if (page.size() < Math.max(limit, 200)) {
                break;
            }
        }
        return low;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareReservation(Set<Sku> skus) {
        // SKU advisory locks only (SCRUM-152): a SKU reservation touches no stock row, so locking every stock row of
        // the basket, as checkout did while it chose lots, would only serialise checkouts against picking.
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
    public StockMove reclassify(ReclassifyStockCommand command) {
        return operations.reclassify(command);
    }

    @Override
    public StockMove receiveReturn(com.stockflow.inventory.api.ReceiveReturnCommand command) {
        return operations.receiveReturn(command);
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
        LocalDate today = BusinessCalendar.date(clock.instant());
        return netOfSkuReservations(allocatableBySku(normalized, today), normalized);
    }

    /**
     * Units a release could allocate, per SKU code (team plan 4C-2; kltn-docs 07 BR-03, 20 §5.6): whole sealed cartons
     * nothing holds, unexpired, on pickable bins (module 06 BR-12), none a recount has frozen (X-SHORT step 03). The
     * same stock {@link PickableStock} offers the planner, across every warehouse: a reservation is promised against
     * the whole system. Loose units and an opened carton are on hand but never allocated, so they are not promised.
     */
    private Map<String, Long> allocatableBySku(Collection<String> skus, LocalDate today) {
        return new PickableStock(repository, locations, cycleCounts).allocatableBySku(skus, today);
    }

    private Set<StockItemId> frozenLayers(Sku sku, LocalDate today) {
        return cycleCounts.frozen(List.of(sku.code()), today).stream()
                .map(frozen -> new StockItemId(frozen.stockItemId())).collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Sku, Integer> availableToPromise(Collection<Sku> skus) {
        Set<Sku> wanted = new LinkedHashSet<>(skus);
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<String> codes = wanted.stream().map(Sku::code).toList();
        Map<String, Long> found = allocatableBySku(codes, today);
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
        var today = BusinessCalendar.date(clock.instant());
        Set<StockItemId> frozen = frozenLayers(sku, today);
        // Use the configured picking order and one business date for the entire result.
        return repository.findAvailableBySku(sku).stream()
                .filter(item -> notExpired(item, today))
                .filter(item -> !frozen.contains(item.id()))
                .sorted(Comparator.comparing(item ->
                                        new StockAllocator.Candidate(
                                                item.id(),
                                                item.location(),
                                                item.lotNumber(),
                                                item.expiryDate(),
                                                item.status(),
                                                item.available(),
                                                item.receivedAt()),
                                StockAllocator.comparator(controls.policy(sku).removalStrategy())))
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
        record Totals(int onHand, int available, int reserved, int atp) {
            static Totals of(StockLevelLine line, LocalDate today) {
                boolean usable = line.status() == StockStatus.AVAILABLE
                                && (line.expiryDate() == null
                                        || !line.expiryDate().isBefore(today));
                int sellable = usable ? line.onHand() : 0;
                return new Totals(line.onHand(), sellable, line.reserved(),
                        usable ? line.onHand() - line.reserved() : 0);
            }

            Totals plus(Totals other) {
                return new Totals(onHand + other.onHand, available + other.available,
                        reserved + other.reserved,
                        atp + other.atp);
            }
        }
        Map<String, Totals> byWarehouse = new TreeMap<>();
        var today = BusinessCalendar.date(clock.instant());
        for (StockLevelLine line : repository.findLevelLinesBySku(sku)) {
            byWarehouse.merge(line.location().warehouseCode(), Totals.of(line, today), Totals::plus);
        }
        // Allocations are holds on these same rows: counted in reserved above, shown apart below (glossary: a
        // reservation is a soft hold on the SKU, an allocation a hard hold on a bin), so the two never add up twice.
        Map<String, Integer> allocatedByWarehouse = new TreeMap<>();
        repository.findAllocatedBySku(sku).forEach((locationCode, units) ->
                allocatedByWarehouse.merge(new LocationId(locationCode).warehouseCode(), units, Integer::sum));
        for (var frozen : cycleCounts.frozen(List.of(sku.code()), today)) {
            byWarehouse.computeIfPresent(new LocationId(frozen.locationCode()).warehouseCode(), (code, totals) ->
                    new Totals(totals.onHand(), totals.available(), totals.reserved(),
                            (int) Math.max(0, totals.atp() - frozen.free())));
        }
        // A SKU reservation is promised against the whole system, not against a warehouse (SCRUM-152): no warehouse
        // can promise more than is left system-wide once those are counted - from these same lines and this same day.
        long unheld = byWarehouse.values().stream().mapToLong(Totals::atp).sum();
        int systemWide = (int) Math.max(0, unheld - skuReservations.heldBySku(List.of(sku.code()))
                .getOrDefault(sku.code(), 0L));
        return byWarehouse.entrySet().stream()
                // BR-SEC-002: a warehouse-bound caller sees their own warehouses' levels only.
                .filter(entry -> com.stockflow.common.security.WarehouseScope.restriction()
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
     * <p><b>This method is the whole argument for the monolith.</b> It joins the caller's
     * transaction ({@code Propagation.REQUIRED}, the default, stated explicitly here because it is
     * load-bearing rather than incidental). {@code order} calls it while saving the order; one
     * commit covers both. If the order insert then violates a constraint, this reservation
     * disappears with it, automatically.</p>
     *
     * <p><b>A SKU reservation, not a choice of lots (SCRUM-152).</b> Until Sprint 3 this method planned FEFO over
     * every location and placed the hold on stock items — choosing a bin and a lot at checkout, before anybody
     * knew which warehouse would ship the order. It now holds quantity against the SKU only; {@link #allocate}
     * chooses bins and lots when the order is released (docs 01, 03 §6, 04c F-FUL).</p>
     *
     * <ol>
     *   <li><b>Replay first.</b> A retried checkout finds the reservation its first attempt made — or, for a retry
     *       of a checkout placed before this change, the holds it placed on stock items.</li>
     *   <li><b>Under the SKU's advisory lock</b> — the lock every path that changes what is promised takes before
     *       any row lock — read available-to-promise: unheld sellable stock minus live SKU reservations.</li>
     *   <li>Open the reservation, refused below that figure. {@code tg_stock_reservation_soft_atp} checks the same
     *       rule again in the database, without the expiry dates, as the safety net.</li>
     * </ol>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReserveStockResult reserve(ReserveStockCommand command) {
        Instant now = clock.instant();
        List<SkuReservation> served = skuReservations.findHeldForRequest(command.requestId());
        if (!served.isEmpty()) {
            log.info("Request {} was already served; returning its reservation", command.requestId());
            return resultOf(command.orderId(), command.sku(), served);
        }
        Optional<ReserveStockResult> placedOnStock = replayOf(command.requestId(), command.sku(), command.orderId());
        if (placedOnStock.isPresent()) {
            return placedOnStock.get();
        }

        policies.lock(command.sku().code());   // re-entrant: checkout took it in prepareReservation already
        requireWholeCartons(command.sku(), command.quantity());
        int available = availableToPromise(command.sku());
        SkuReservation reservation = SkuReservation.open(command.sku(), command.orderId(), command.requestId(),
                Quantity.of(command.quantity()), available, now, reservationTtl);
        skuReservations.save(reservation);
        events.publishEventsOf(reservation);

        log.info("Reserved {} units of {} for order {} (SKU reservation {})",
                command.quantity(), command.sku(), command.orderId(), reservation.id());
        return new ReserveStockResult(command.orderId(), List.of(toApiReservation(reservation)), command.quantity(),
                available - command.quantity(), now);
    }

    /**
     * A reservation counts cartons (kltn-docs 07 BR-03, 20 §5.6): the units asked for are a whole number of the SKU's
     * cartons, since a release hands out whole cartons only and a remainder could never be allocated. A SKU with no
     * carton unit has no stock in cartons, so nothing would be promised to it anyway.
     */
    private void requireWholeCartons(Sku sku, int quantity) {
        java.util.OptionalInt factor = stockPackages.cartonFactorOf(sku.code());
        if (factor.isPresent() && quantity % factor.getAsInt() != 0) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_WHOLE_CARTONS,
                    "%d unit(s) of %s are not whole cartons of %d; a reservation counts cartons".formatted(quantity,
                            sku.code(), factor.getAsInt()), List.of(),
                    Map.of("sku", sku.code(), "cartonQuantity", factor.getAsInt()));
        }
    }

    private ReserveStockResult resultOf(UUID orderId, Sku sku, List<SkuReservation> reservations) {
        return new ReserveStockResult(orderId, reservations.stream().map(InventoryServiceImpl::toApiReservation).toList(),
                reservations.stream().mapToInt(r -> r.quantity().value()).sum(), availableToPromise(sku),
                reservations.stream().map(SkuReservation::reservedAt).min(Comparator.naturalOrder()).orElseThrow());
    }

    private static StockReservation toApiReservation(SkuReservation reservation) {
        return new StockReservation(reservation.id().value(), null, null, null, reservation.quantity().value());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The order of locks is the one every checkout and move uses: the SKUs' advisory locks (sorted), then the
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
        List<SkuReservation> unlocked = skuReservations.findHeldForOrder(command.orderId());
        if (unlocked.isEmpty()) {
            requireNoneLapsed(command.orderId());
            return new AllocateStockResult(command.orderId(), warehouse, List.of());
        }
        unlocked.stream().map(r -> r.sku().code()).distinct().sorted().forEach(policies::lock);
        List<SkuReservation> held = skuReservations.findHeldForOrderForUpdate(command.orderId());
        // After the lock: a reservation the sweeper expired a moment ago is seen as expired here, not skipped.
        requireNoneLapsed(command.orderId());
        LocalDate today = BusinessCalendar.date(now);

        record Planned(SkuReservation from, int sequence, StockAllocator.AllocationLine line) { }
        List<Planned> planned = new ArrayList<>();
        Map<Sku, List<StockAllocator.Candidate>> pools = new LinkedHashMap<>();
        int sequence = 0;
        for (SkuReservation reservation : held) {
            List<StockAllocator.Candidate> pool = pools.computeIfAbsent(reservation.sku(),
                    sku -> pickableCandidates(sku, warehouse, null));
            List<StockAllocator.AllocationLine> lines = plan(reservation.sku(), pool, reservation.quantity(),
                    warehouse, today);
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
        LocalDate today = BusinessCalendar.date(now);
        if (restoredImpairment(shortId, now)) {
            // A damage report's impaired allocation whose units a manager found fine and restored onto a bin, the
            // order's share with them: a sound allocation on pickable stock now, nothing about that bin in doubt. Kept
            // where it is; the short line gives way to one that picks it there.
            StockItem current = repository.findByIdForUpdate(view.id()).orElseThrow();
            Reservation hold = current.findReservation(shortId).orElseThrow();
            log.info("Allocation {} of order {}: restored from damage at {}, kept for picking", shortId,
                    command.orderId(), current.location());
            return List.of(toApiReservation(current, hold));
        }
        String warehouse = view.location().warehouseCode();
        List<StockAllocator.AllocationLine> lines = plan(view.sku(),
                pickableCandidates(view.sku(), warehouse, view.id()), shortHold.quantity(), warehouse, today);
        UUID origin = shortHold.allocatedFrom() != null ? shortHold.allocatedFrom() : shortHold.id().value();

        Set<StockItemId> ids = new java.util.TreeSet<>(Comparator.comparing(id -> id.value().toString()));
        ids.add(view.id());
        lines.forEach(line -> ids.add(line.stockItemId()));
        Map<StockItemId, StockReservation> made = new LinkedHashMap<>();
        for (StockItemId id : ids) {
            StockItem locked = repository.findByIdForUpdate(id).orElseThrow();
            if (id.equals(view.id())) {
                Reservation current = locked.findReservation(shortId).orElseThrow();
                if (!current.status().isActive()) {
                    throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                            "Allocation %s is already %s".formatted(shortId, current.status()));
                }
                locked.releaseReservation(shortId, ReleaseReason.SHORT_PICKED, now);
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
     * Whether the allocation is a damage report's impaired share that went back onto good stock with its units
     * (RESTORE of everything left): AVAILABLE, unexpired, on a pickable location of a layer no count has frozen.
     * Read under the SKU lock.
     */
    private boolean restoredImpairment(ReservationId holdId, Instant now) {
        if (!damageReports.isImpairedAllocation(holdId.value())) {
            return false;
        }
        StockItem item = repository.findIdByReservationId(holdId).flatMap(repository::findById).orElseThrow();
        LocalDate today = BusinessCalendar.date(now);
        boolean frozen = cycleCounts.frozen(List.of(item.sku().code()), today).stream()
                .anyMatch(layer -> layer.stockItemId().equals(item.id().value()));
        return item.findReservation(holdId).map(r -> r.status().isActive()).orElse(false)
                && item.status() == StockStatus.AVAILABLE
                && !com.stockflow.inventory.internal.domain.ExpiryPolicy.isExpired(item.expiryDate(), today)
                && !frozen
                && locations.pickable(List.of(item.location().code())).containsKey(item.location().code());
    }

    /**
     * Sellable stock of the SKU on pickable, usable locations of one warehouse (module 06 BR-12), never at
     * {@code excluded}; read as projections, so nothing lands in the persistence context before the locked reads.
     * The one definition, shared with the damage reported at picking: {@link PickableStock}.
     */
    private List<StockAllocator.Candidate> pickableCandidates(Sku sku, String warehouse, StockItemId excluded) {
        return new PickableStock(repository, locations, cycleCounts).candidates(sku, warehouse, excluded, clock.instant());
    }

    /**
     * All or nothing (docs 04c F-FUL): a line whose reservation expired or was released before the release has no
     * stock behind it, and allocating the other lines would send the order to the warehouse short. Only a
     * cancellation releases an order's reservations, and an expiry cancels an unpaid order, so this is the race
     * where the release came first.
     */
    private void requireNoneLapsed(UUID orderId) {
        if (skuReservations.anyLapsedForOrder(orderId)) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "A reservation of order %s expired or was released; the order cannot be allocated".formatted(orderId));
        }
    }

    /**
     * Whole cartons through {@link StockAllocator}, in the SKU's own removal order; names the warehouse when short.
     * All or nothing for now: a warehouse with only k &lt; N cartons refuses (07 BR-03.4 holds the k and sends N − k to
     * a Production Order, but until the order can wait for them - {@code AWAITING_STOCK}, team plan 4A-6 - an order
     * released short would go to the floor with nothing tracking what it lacks).
     */
    private List<StockAllocator.AllocationLine> plan(Sku sku, List<StockAllocator.Candidate> pool, Quantity quantity,
                                                     String warehouse, LocalDate today) {
        StockAllocator.Plan plan = StockAllocator.plan(sku.code(), pool, quantity,
                controls.policy(sku).removalStrategy(), today);
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

    /**
     * Rebuilds the original result when this request has already been served.
     *
     * <p>Reads the quantities from the stored reservations rather than from the command, so a
     * partially-applied earlier attempt reports what is actually held rather than what was asked
     * for.</p>
     */
    private Optional<ReserveStockResult> replayOf(UUID requestId, Sku sku, UUID orderId) {
        List<StockItem> holders = repository.findWithReservationsForRequest(requestId)
                .stream()
                // Same id ordering as the reserve loop. These loads take write locks too, and two
                // replays of different requests touching the same pair of stock items in opposite
                // orders would deadlock exactly as two fresh checkouts would.
                .sorted(Comparator.comparing(item -> item.id().value().toString()))
                .toList();
        if (holders.isEmpty()) {
            return Optional.empty();
        }

        List<StockReservation> reservations = new ArrayList<>();
        Instant earliest = null;
        for (StockItem item : holders) {
            for (Reservation reservation : item.activeReservationsFor(requestId)) {
                reservations.add(toApiReservation(item, reservation));
                if (earliest == null || reservation.reservedAt().isBefore(earliest)) {
                    earliest = reservation.reservedAt();
                }
            }
        }
        if (reservations.isEmpty()) {
            return Optional.empty();
        }

        int quantityHeld = reservations.stream().mapToInt(StockReservation::quantity).sum();
        return Optional.of(new ReserveStockResult(orderId, reservations, quantityHeld,
                availableToPromise(sku), earliest));
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

    /**
     * {@inheritDoc}
     *
     * <p>{@code findWithReservationsForOrder} locks each stock row, the same lock the expiry sweep
     * takes, so a hold is either pinned here or expired there — never both.</p>
     */
    @Override
    public int pinReservations(UUID orderId) {
        int pinned = 0;
        for (SkuReservation reservation : skuReservations.findHeldForOrderForUpdate(orderId)) {
            if (reservation.pin()) {
                skuReservations.save(reservation);
                pinned++;
            }
        }
        for (StockItem item : repository.findWithReservationsForOrder(orderId)) {
            int here = item.pinReservationsOf(orderId);
            if (here > 0) {
                repository.save(item);
                pinned += here;
            }
        }
        if (pinned > 0) {
            log.info("Pinned {} hold(s) of order {}: it no longer waits for payment", pinned, orderId);
        }
        return pinned;
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
                        hold.expiryDate(), hold.serialNumber(), hold.lpn(), hold.wholeCarton()))
                .toList());
        skuReservations.findAll(Set.copyOf(reservationIds)).forEach(r -> views.add(
                new com.stockflow.inventory.api.HoldView(r.id().value(), r.sku().code(), null, null,
                        r.quantity().value(), com.stockflow.inventory.api.HoldView.Status.valueOf(r.status().name()),
                        r.expiresAt(), null, null)));
        return views;
    }

    @Override
    public int rearmReservations(UUID orderId) {
        Instant deadline = clock.instant().plus(reservationTtl);
        int rearmed = 0;
        for (SkuReservation reservation : skuReservations.findHeldForOrderForUpdate(orderId)) {
            if (reservation.rearm(deadline)) {
                skuReservations.save(reservation);
                rearmed++;
            }
        }
        // Allocations do not expire (X-RESV): Reservation#rearm leaves them pinned.
        for (StockItem item : repository.findWithReservationsForOrder(orderId)) {
            int here = item.rearmReservationsOf(orderId, deadline);
            if (here > 0) {
                repository.save(item);
                rearmed += here;
            }
        }
        if (rearmed > 0) {
            log.info("Order {} waits for payment again: {} hold(s) expire at {}", orderId, rearmed, deadline);
        }
        return rearmed;
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
     * holds in their own warehouses (BR-SEC-002); the system — an event listener, the sweeper — is
     * not limited.
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
     * stable across processes and restarts, so a retried checkout is recognised as a replay rather
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
                item.expiryDate(),
                item.onHand().value(),
                item.reserved().value(),
                0,
                item.available().value());
    }

    private static boolean notExpired(StockItem item, LocalDate today) {
        return item.expiryDate() == null || !item.expiryDate().isBefore(today);
    }
}
