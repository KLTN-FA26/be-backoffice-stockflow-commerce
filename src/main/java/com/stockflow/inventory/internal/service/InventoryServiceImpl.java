package com.stockflow.inventory.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.ReallocateStockCommand;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
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

    /** How long a hold lasts while its order waits for payment (kltn-docs 14: set by the business). */
    private final java.time.Duration reservationTtl;

    InventoryServiceImpl(StockItemRepository repository, InventoryEventPublisher events,
                         StockOperations operations, InventoryItemDirectory items, Clock clock,
                         InventoryControlService controls, InventoryPolicyRepository policies,
                         SkuReservationRepository skuReservations, LocationDirectory locations,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${stockflow.inventory.reservation-ttl:PT30M}") java.time.Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("stockflow.inventory.reservation-ttl must be positive");
        }
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

    /** Delegated: moves and adjustments write the ledger, which {@link StockOperations} owns. */
    @Override
    public StockMove move(MoveStockCommand command) {
        return operations.move(command);
    }

    @Override
    public StockMove receive(ReceiveStockCommand command) {
        return operations.receive(command);
    }

    @Override
    public StockMove reclassify(ReclassifyStockCommand command) {
        return operations.reclassify(command);
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
        return netOfSkuReservations(repository.availableQuantities(normalized, BusinessCalendar.date(clock.instant())),
                normalized);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Sku, Integer> availableToPromise(Collection<Sku> skus) {
        Set<Sku> wanted = new LinkedHashSet<>(skus);
        Map<Sku, Integer> found = repository.sumAvailableBySkus(wanted, BusinessCalendar.date(clock.instant()));
        Map<String, Long> reserved = skuReservations.heldBySku(wanted.stream().map(Sku::code).toList());
        Map<Sku, Integer> result = new LinkedHashMap<>();
        // Every SKU asked for gets an answer: no stock is an answer (0), not a missing key.
        wanted.forEach(sku -> result.put(sku, (int) Math.max(0,
                found.getOrDefault(sku, 0) - reserved.getOrDefault(sku.code(), 0L))));
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
        // Use the configured picking order and one business date for the entire result.
        return repository.findAvailableBySku(sku).stream()
                .filter(item -> notExpired(item, today))
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
                .map(entry -> new StockLevel(sku.code(), entry.getKey(),
                        entry.getValue().onHand(), entry.getValue().available(),
                        entry.getValue().reserved(), 0,
                        Math.min(entry.getValue().atp(), systemWide)))
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
            return new AllocateStockResult(command.orderId(), warehouse, List.of());
        }
        unlocked.stream().map(r -> r.sku().code()).distinct().sorted().forEach(policies::lock);
        List<SkuReservation> held = skuReservations.findHeldForOrderForUpdate(command.orderId());
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

    @Override
    @Transactional(readOnly = true)
    public List<UUID> warehousesAbleToAllocate(UUID orderId) {
        Map<Sku, Integer> needed = new TreeMap<>(Comparator.comparing(Sku::code));
        skuReservations.findHeldForOrder(orderId)
                .forEach(r -> needed.merge(r.sku(), r.quantity().value(), Integer::sum));
        if (needed.isEmpty()) {
            return List.of();
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        Set<UUID> able = null;
        for (Map.Entry<Sku, Integer> want : needed.entrySet()) {
            List<StockAllocator.Candidate> candidates = repository.findAvailabilityBySku(want.getKey()).stream()
                    .filter(c -> c.expiryDate() == null || !c.expiryDate().isBefore(today))
                    .toList();
            Map<String, UUID> pickable = locations.pickable(candidates.stream().map(c -> c.location().code()).toList());
            Map<UUID, Integer> free = new java.util.HashMap<>();
            candidates.stream().filter(c -> pickable.containsKey(c.location().code()))
                    .forEach(c -> free.merge(pickable.get(c.location().code()), c.available().value(), Integer::sum));
            Set<UUID> enough = free.entrySet().stream().filter(e -> e.getValue() >= want.getValue())
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            if (able == null) {
                able = new java.util.HashSet<>(enough);
            } else {
                able.retainAll(enough);
            }
        }
        return able.stream().sorted(Comparator.comparing(UUID::toString)).toList();
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
        String warehouse = view.location().warehouseCode();
        List<StockAllocator.AllocationLine> lines = plan(view.sku(),
                pickableCandidates(view.sku(), warehouse, view.location()), shortHold.quantity(), warehouse, today);
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
     * Sellable stock of the SKU on pickable, usable locations of one warehouse (module 06 BR-12), never at
     * {@code excluded}; read as projections, so nothing lands in the persistence context before the locked reads.
     */
    private List<StockAllocator.Candidate> pickableCandidates(Sku sku, String warehouse, LocationId excluded) {
        List<StockAllocator.Candidate> inWarehouse = repository.findAvailabilityBySku(sku).stream()
                .filter(c -> c.location().warehouseCode().equals(warehouse))
                .filter(c -> !c.location().equals(excluded))
                .toList();
        Map<String, UUID> pickable = locations.pickable(inWarehouse.stream().map(c -> c.location().code()).toList());
        return inWarehouse.stream().filter(c -> pickable.containsKey(c.location().code())).toList();
    }

    /** BR-STK-006 through {@link StockAllocator}, with the SKU's own removal strategy; names the warehouse when short. */
    private List<StockAllocator.AllocationLine> plan(Sku sku, List<StockAllocator.Candidate> pool, Quantity quantity,
                                                     String warehouse, LocalDate today) {
        try {
            return StockAllocator.plan(sku.code(), pool, quantity, controls.policy(sku).removalStrategy(), today);
        } catch (InsufficientStockException shortage) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "Warehouse %s has %d pickable unit(s) of %s; %d are needed".formatted(warehouse,
                            shortage.available(), sku.code(), quantity.value()));
        }
    }

    /** The pool after {@code taken}: what a second line of the same SKU may still draw on. */
    private static List<StockAllocator.Candidate> without(List<StockAllocator.Candidate> pool,
                                                          List<StockAllocator.AllocationLine> taken) {
        Map<StockItemId, Integer> used = new java.util.HashMap<>();
        taken.forEach(line -> used.merge(line.stockItemId(), line.quantity().value(), Integer::sum));
        return pool.stream().map(c -> {
            int left = c.available().value() - used.getOrDefault(c.stockItemId(), 0);
            return new StockAllocator.Candidate(c.stockItemId(), c.location(), c.lotNumber(), c.expiryDate(),
                    c.status(), Quantity.of(Math.max(left, 0)), c.receivedAt());
        }).toList();
    }

    /** Same reservation and stock item, same key: allocating again after a retry finds the first allocation. */
    private static UUID allocationRequestId(ReservationId from, StockItemId stockItemId) {
        return UUID.nameUUIDFromBytes(("allocation:" + from.value() + ":" + stockItemId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The steps of {@link #reserve}: replay first, plan over a projection, lock in id order, re-check
     * under the lock. The plan draws only on the production order's warehouse.</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReserveStockResult reserveForProduction(ReserveProductionStockCommand command) {
        Instant now = clock.instant();
        String warehouse = new LocationId(command.warehouseCode()).code();
        Optional<ReserveStockResult> alreadyServed = replayOf(command.requestId(), command.sku(), null);
        if (alreadyServed.isPresent()) {
            return alreadyServed.get();
        }
        // SCRUM-152: checkout reservations sit on no stock item, so a production hold taken from the warehouse's
        // free stock must also leave every one of them covered - under the SKU lock checkout takes.
        policies.lock(command.sku().code());
        int systemWide = availableToPromise(command.sku());
        if (systemWide < command.quantity()) {
            throw new InsufficientStockException(command.sku().code(), command.quantity(), systemWide);
        }
        List<StockAllocator.Candidate> candidates = repository.findAvailabilityBySku(command.sku()).stream()
                .filter(candidate -> candidate.location().warehouseCode().equals(warehouse))
                .toList();
        List<StockAllocator.AllocationLine> ordered = StockAllocator.plan(
                        command.sku().code(), candidates, Quantity.of(command.quantity()),
                        controls.policy(command.sku()).removalStrategy(), BusinessCalendar.date(now))
                .stream()
                .sorted(Comparator.comparing(line -> line.stockItemId().value().toString()))
                .toList();
        List<StockReservation> reservations = new ArrayList<>();
        for (StockAllocator.AllocationLine line : ordered) {
            StockItem locked = repository.findByIdForUpdate(line.stockItemId()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-reservation".formatted(line.stockItemId())));
            Reservation reservation = locked.reserveForProduction(command.productionOrderId(), command.requestId(),
                    perStockItemRequestId(command.requestId(), line.stockItemId()), line.quantity(), now);
            repository.save(locked);
            reservations.add(toApiReservation(locked, reservation));
        }
        log.info("Held {} units of {} in {} for production order {} across {} stock item(s)", command.quantity(),
                command.sku(), warehouse, command.productionOrderId(), reservations.size());
        return new ReserveStockResult(null, reservations, command.quantity(),
                availableToPromise(command.sku(), warehouse), now);
    }

    @Override
    public int releaseProductionHolds(UUID productionOrderId) {
        Instant now = clock.instant();
        int released = 0;
        // SKU locks first, rows second: the order every move and checkout takes them in (review of #109, item 4).
        // Locking the rows first and a move of the same transaction taking the SKU lock after (a completion
        // releasing unissued blanks, then handing the goods to packing) can deadlock against a checkout (40P01).
        productionHoldViews(productionOrderId).stream().map(com.stockflow.inventory.api.HoldView::sku).distinct()
                .sorted().forEach(policies::lock);
        for (StockItem item : repository.findWithReservationsForProductionOrder(productionOrderId)) {
            if (item.status() != StockStatus.AVAILABLE) {
                continue;   // issued, received for production, or finished: production closes those itself
            }
            List<Reservation> live = item.reservations().stream()
                    .filter(r -> r.status().isActive() && productionOrderId.equals(r.productionOrderId()))
                    .toList();
            for (Reservation hold : live) {
                item.releaseReservation(hold.id(), ReleaseReason.PRODUCTION_CANCELLED, now);
                released += hold.quantity().value();
            }
            if (!live.isEmpty()) {
                repository.save(item);
            }
        }
        if (released > 0) {
            log.info("Released {} unit(s) held for production order {}", released, productionOrderId);
        }
        return released;
    }

    /**
     * Read without locking anything (review of #109, item 4). Every caller then moves or releases some of these holds
     * through a path that takes the SKU lock first and the stock rows second; locking the rows here, before that SKU
     * lock, inverted the order every checkout and move use, and two such transactions could deadlock (40P01). The
     * production order's own row lock is what keeps two steps of one order apart.
     */
    @Override
    public List<StockReservation> productionHolds(UUID productionOrderId) {
        return liveHolds(repository.findHeldReservationIdsOfProductionOrder(productionOrderId)).stream()
                .sorted(java.util.Comparator.comparing(hold -> hold.stockItemId().toString()))
                .toList();
    }

    /** Delegated: the hold moves with its goods and the ledger records it, see {@link StockOperations}. */
    @Override
    public IssueReservedStockResult issueReserved(IssueReservedStockCommand command) {
        if (skuReservations.exists(ReservationId.of(command.reservationId()))) {
            // SCRUM-152: a SKU reservation holds no goods yet - the order's release allocates it to a bin first.
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Reservation %s is not allocated to a bin yet; the order must be released first"
                            .formatted(command.reservationId()));
        }
        return operations.issueReserved(command);
    }

    @Override
    public StockReservation holdReceived(com.stockflow.inventory.api.HoldReceivedStockCommand command) {
        return operations.holdReceived(command);
    }

    @Override
    public StockMove consumeHeld(com.stockflow.inventory.api.ConsumeHeldStockCommand command) {
        return operations.consumeHeld(command);
    }

    @Override
    public IssueReservedStockResult unissueHeld(IssueReservedStockCommand command) {
        return operations.unissueHeld(command);
    }

    @Override
    public IssueReservedStockResult finishHeld(IssueReservedStockCommand command) {
        return operations.finishHeld(command);
    }

    @Override
    public StockAdjustmentSummary writeOffHeld(com.stockflow.inventory.api.WriteOffHeldStockCommand command) {
        return operations.writeOffHeld(command);
    }

    @Override
    public Optional<StockAdjustmentSummary> scrapCancelledPrint(UUID reservationId, UUID orderId, String note,
                                                                UUID requestedBy) {
        if (skuReservations.exists(ReservationId.of(reservationId))) {
            return Optional.empty();    // never allocated, so nothing was ever printed from it
        }
        return operations.scrapCancelledPrint(reservationId, orderId, note, requestedBy);
    }

    @Override
    public StockMove returnHeld(com.stockflow.inventory.api.ReturnHeldStockCommand command) {
        return operations.returnHeld(command);
    }

    @Override
    public void handHoldToOrder(UUID reservationId, UUID productionOrderId, UUID orderId) {
        ReservationId id = ReservationId.of(reservationId);
        StockItem item = holderOf(id);
        Reservation hold = item.findReservation(id).orElseThrow();
        if (hold.status().isActive() && orderId.equals(hold.orderId())) {
            return;
        }
        if (!hold.status().isActive() || !productionOrderId.equals(hold.productionOrderId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s is not a live hold of production order %s".formatted(reservationId, productionOrderId));
        }
        item.handHoldToOrder(id, productionOrderId, orderId);
        repository.save(item);
        log.info("Hold {} of production order {} is now order {}'s", reservationId, productionOrderId, orderId);
    }

    @Override
    public void reduceHold(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity) {
        ReservationId id = ReservationId.of(reservationId);
        // The SKU lock before the row lock, the order allocation and checkout take them in: the subcontract flow
        // reduces a hold and then holds blanks again for the child order in the same transaction.
        repository.findHolds(List.of(reservationId)).stream().findFirst()
                .ifPresent(hold -> policies.lock(hold.sku()));
        StockItem item = holderOf(id);
        Reservation hold = item.findReservation(id).orElseThrow();
        if (!hold.isHeldFor(orderId, productionOrderId) || !hold.status().isActive()
                || item.status() == StockStatus.IN_PRODUCTION || quantity < 1
                || hold.quantity().value() < quantity) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s cannot give up %d unit(s) here".formatted(reservationId, quantity));
        }
        item.reduceHold(id, Quantity.of(quantity), ReleaseReason.SUBCONTRACTED, clock.instant());
        repository.save(item);
        log.info("Hold {} gave up {} unit(s) for subcontracting", reservationId, quantity);
    }

    @Override
    public List<StockReservation> liveHolds(Collection<UUID> reservationIds) {
        List<StockReservation> live = new ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(reservationIds)) {
            repository.findIdByReservationId(ReservationId.of(id)).flatMap(repository::findById).ifPresent(item ->
                    item.findReservation(ReservationId.of(id)).filter(r -> r.status().isActive())
                            .ifPresent(r -> live.add(toApiReservation(item, r))));
        }
        return live;
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
    public List<com.stockflow.inventory.api.HoldView> productionHoldViews(UUID productionOrderId) {
        List<UUID> ids = repository.findHeldReservationIdsOfProductionOrder(productionOrderId);
        Map<UUID, com.stockflow.inventory.api.HoldView> byId = holds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(com.stockflow.inventory.api.HoldView::reservationId,
                        java.util.function.Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.stockflow.inventory.api.HoldView> holds(Collection<UUID> reservationIds) {
        List<com.stockflow.inventory.api.HoldView> views = new ArrayList<>(
                repository.findHolds(Set.copyOf(reservationIds)).stream()
                .map(hold -> new com.stockflow.inventory.api.HoldView(hold.reservationId(), hold.sku(),
                        hold.locationCode(), hold.lotNumber(), hold.quantity(),
                        com.stockflow.inventory.api.HoldView.Status.valueOf(hold.status().name()), hold.expiresAt(), hold.expiryDate()))
                .toList());
        skuReservations.findAll(Set.copyOf(reservationIds)).forEach(r -> views.add(
                new com.stockflow.inventory.api.HoldView(r.id().value(), r.sku().code(), null, null,
                        r.quantity().value(), com.stockflow.inventory.api.HoldView.Status.valueOf(r.status().name()),
                        r.expiresAt(), null)));
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
     * <p>A hold whose goods are in the print shop is <b>not</b> released here (SCRUM-401, Q3): an order cancelled
     * after its blanks were issued releases its holds in its own transaction, before production knows. The goods
     * stay held, unsellable, until production stops the job and decides unit by unit — printed goods written
     * off, unprinted blanks returned ({@link #returnHeld}). Releasing here would leave production unable to tell
     * the printed units from the blank ones.</p>
     */
    @Override
    public void release(UUID reservationId, String reason) {
        ReleaseReason releaseReason = parseReason(reason);
        if (skuReservations.exists(ReservationId.of(reservationId))) {
            releaseSkuReservation(ReservationId.of(reservationId), releaseReason);
            return;
        }
        StockItem item = holderOf(ReservationId.of(reservationId));
        if (item.status() == StockStatus.IN_PRODUCTION) {
            log.info("Hold {} is in the print shop; production closes it, not a {} release", reservationId,
                    releaseReason);
            return;
        }
        // BR-PRD-10: printed for this order, waiting in packing - packed with it, or scrapped (scrapCancelledPrint),
        // never freed for someone else to buy.
        Reservation hold = item.findReservation(ReservationId.of(reservationId)).orElseThrow();
        if (operations.isFinishedCustomGoods(item, hold)) {
            log.info("Hold {} is printed goods in packing; it is packed or scrapped, not a {} release", reservationId,
                    releaseReason);
            return;
        }

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
