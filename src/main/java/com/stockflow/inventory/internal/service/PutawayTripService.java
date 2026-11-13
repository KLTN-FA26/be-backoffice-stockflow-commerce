package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery;
import com.stockflow.inventory.internal.repository.BinOccupancyQuery.CartonLoad;
import com.stockflow.inventory.internal.service.PutawayService.Reserved;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.PutawayTripView;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Putting away by the cart (kltn-docs 05 §4.5; Jira SCRUM-531): a clerk scans cartons onto a cart, sets off, and the
 * system gives every carton a bin, holds the space and orders the stops. The warehouse module keeps the trip
 * ({@link PutawayTrips}); which bin takes which carton is a fact of the stock, so it is decided here, with the very
 * rules a single putaway is suggested by ({@link PutawayService#suggest}).
 *
 * <p><b>Assigning the cart</b> ({@link #start}). Cartons are taken lot by lot, in the order each lot first came onto
 * the cart, so the cartons of one lot get their bins one after another (05 §4.5 step 13). Each is given the first
 * bin the ranking offers; what it takes there counts as taken for the next carton, so two cartons are never given
 * the same free space. The space other trips hold counts as taken too (BR-16). A carton no bin takes is given the
 * warehouse's OVERFLOW area (BR-13), or nothing when the warehouse has none.</p>
 *
 * <p><b>Putting a carton away</b> is {@link PutawayService#confirm}, unchanged: the bin scanned is judged by the hard
 * rules whether it is the one assigned or not (BR-07, BR-09), and a hold is not a lock (BR-16). The trip is told by
 * the putaway itself, so a carton of a cart put away from the single-task screen leaves its cart all the same.</p>
 *
 * <p>Lock order: the trip row first, then whatever a putaway locks (task row, bin, SKU, carton, stock rows).</p>
 *
 * <p>A trip is worked by its own clerk; a warehouse manager may close one left behind (05 §4.5 step 17), which the
 * controller lets through under its own permission.</p>
 */
@Service
public class PutawayTripService {

    private final PutawayTrips trips;
    private final PutawayTasks tasks;
    private final PutawayService putaway;
    private final WarehouseService warehouses;
    private final StockPackageRepository stockPackages;
    private final BinOccupancyQuery occupancy;

    public PutawayTripService(PutawayTrips trips, PutawayTasks tasks, PutawayService putaway,
                              WarehouseService warehouses, StockPackageRepository stockPackages,
                              BinOccupancyQuery occupancy) {
        this.trips = trips;
        this.tasks = tasks;
        this.putaway = putaway;
        this.warehouses = warehouses;
        this.stockPackages = stockPackages;
        this.occupancy = occupancy;
    }

    /**
     * @param startLocationCode the source area the route starts from; null until the trip set off
     * @param stops             the places still to visit, in order; empty unless the trip is on its way
     */
    public record TripView(UUID id, UUID warehouseId, UUID operatorId, String status, String startLocationCode,
                           BigDecimal startX, BigDecimal startY, Instant startedAt, Instant lastActivityAt,
                           Instant closedAt, UUID closedBy, List<CartonView> cartons, List<StopView> stops) {
    }

    /**
     * @param status               {@code ON_CART}, {@code PLACED} or {@code RETURNED}
     * @param assignedLocationCode the bin (or OVERFLOW area) given when the trip set off; null before
     * @param holding              whether its space there is still held
     */
    public record CartonView(UUID putawayTaskId, String lpn, String sku, String lotNumber, String status,
                             String assignedLocationCode, Integer stopSequence, boolean holding, Instant loadedAt,
                             Instant placedAt) {
    }

    /** A stop of the route: where to stand, and the cartons to put there. */
    public record StopView(int sequence, String locationCode, BigDecimal x, BigDecimal y, List<String> lpns) {
    }

    /**
     * Opens a trip in a warehouse for the caller.
     *
     * @throws BusinessException 403 for a warehouse the caller is not assigned to; {@code PUTAWAY_TRIP_ALREADY_OPEN}
     */
    @Transactional
    public TripView open(UUID warehouseId, UUID actorId) {
        WarehouseScope.requireWarehouse(warehouseId);
        if (warehouses.findWarehouse(warehouseId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "No warehouse " + warehouseId);
        }
        return view(trips.open(warehouseId, actorId));
    }

    /** The caller's open trip, if any. */
    @Transactional(readOnly = true)
    public Optional<TripView> current(UUID actorId) {
        return trips.openTripOf(actorId).map(this::view);
    }

    /** @throws BusinessException {@code PUTAWAY_TRIP_NOT_FOUND}; 403 for a trip of another warehouse */
    @Transactional(readOnly = true)
    public TripView get(UUID tripId) {
        PutawayTripView trip = trips.find(tripId).orElseThrow(() -> new BusinessException(
                ErrorCode.PUTAWAY_TRIP_NOT_FOUND, "Putaway trip " + tripId + " not found"));
        WarehouseScope.requireWarehouse(trip.warehouseId());
        return view(trip);
    }

    /**
     * Scans a carton onto the cart (05 §4.5 step 12): one with a pending putaway task, standing in the trip's
     * warehouse, on nobody else's cart.
     *
     * @throws BusinessException {@code PUTAWAY_TRIP_NOT_YOURS}; {@code STOCK_PACKAGE_NOT_FOUND};
     *         {@code PUTAWAY_TASK_NOT_FOUND} for a carton with no open task; {@code MOVE_ACROSS_WAREHOUSES};
     *         {@code PUTAWAY_TASK_ON_ANOTHER_TRIP}; {@code PUTAWAY_TRIP_INVALID_STATE} once the cart has left
     */
    @Transactional
    public TripView load(UUID tripId, String lpn, UUID actorId) {
        PutawayTripView trip = own(trips.lockForUpdate(tripId), actorId);
        StockPackage carton = carton(lpn);
        PutawayTaskView task = tasks.openTaskOf(carton.id().value()).orElseThrow(() -> new BusinessException(
                ErrorCode.PUTAWAY_TASK_NOT_FOUND, "Carton %s has no putaway task waiting".formatted(carton.lpn())));
        StorageLocationView stands = warehouses.findLocation(carton.location().code()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + carton.location().code()));
        if (!trip.warehouseId().equals(stands.warehouseId())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "Carton %s stands in another warehouse than this trip".formatted(carton.lpn()));
        }
        return view(trips.load(tripId, task.id()));
    }

    /** Takes a carton off the cart before setting off. */
    @Transactional
    public TripView unload(UUID tripId, String lpn, UUID actorId) {
        own(trips.lockForUpdate(tripId), actorId);
        return view(trips.unload(tripId, taskOnCart(tripId, lpn).id()));
    }

    /**
     * Sets off (05 §4.5 steps 13-14): assigns a bin to every carton on the cart, holds the space and orders the stops.
     *
     * @throws BusinessException {@code PUTAWAY_TRIP_NOT_YOURS}, {@code PUTAWAY_TRIP_EMPTY},
     *         {@code PUTAWAY_TRIP_INVALID_STATE}
     */
    @Transactional
    public TripView start(UUID tripId, UUID actorId) {
        PutawayTripView trip = own(trips.lockForUpdate(tripId), actorId);
        List<PutawayTripView.Carton> cart = trip.cartons().stream().filter(c -> "ON_CART".equals(c.status())).toList();
        Map<UUID, PutawayTaskView> taskOf = new LinkedHashMap<>();
        for (PutawayTripView.Carton c : cart) {
            tasks.find(c.putawayTaskId()).ifPresent(task -> taskOf.put(task.id(), task));
        }
        Map<UUID, StockPackage> cartonOf = cartonsOf(taskOf.values());

        Map<UUID, UUID> assigned = assign(trip.warehouseId(), tripId, new ArrayList<>(taskOf.values()));

        // The route starts where the last carton came from (05 §4.5 step 14).
        UUID startLocationId = cart.stream().max(Comparator.comparing(PutawayTripView.Carton::loadedAt))
                .map(last -> taskOf.get(last.putawayTaskId()))
                .map(task -> cartonOf.get(task.stockPackageId()))
                .flatMap(carton -> warehouses.findLocation(carton.location().code()))
                .map(StorageLocationView::id).orElse(null);
        return view(trips.start(tripId, assigned, startLocationId));
    }

    /**
     * Gives each carton of the cart a location: the best bin the ranking offers once the space held by other trips
     * and given to the cartons before it is counted as taken, else the OVERFLOW area. Cartons of one lot follow one
     * another.
     */
    private Map<UUID, UUID> assign(UUID warehouseId, UUID tripId, List<PutawayTaskView> cart) {
        Map<UUID, CartonLoad> loads = occupancy.loadsOf(cart.stream().map(PutawayTaskView::stockPackageId).toList());
        Map<UUID, String> lotOf = new HashMap<>();
        cart.forEach(task -> lotOf.put(task.id(), putaway.lotOfTask(task)));
        // Lot by lot, each lot where its first carton came onto the cart.
        Map<String, List<PutawayTaskView>> byLot = new LinkedHashMap<>();
        for (PutawayTaskView task : cart) {
            String lot = lotOf.get(task.id());
            byLot.computeIfAbsent(task.sku() + "|" + (lot == null ? task.id() : lot), key -> new ArrayList<>())
                    .add(task);
        }
        List<Reserved> reserved = new ArrayList<>(putaway.heldByTrips(warehouseId, null));
        Map<String, UUID> binIds = warehouses.putawayCandidates(warehouseId).stream()
                .collect(Collectors.toMap(c -> c.locationCode(), c -> c.locationId(), (a, b) -> a));
        UUID overflow = overflowArea(warehouseId);

        Map<UUID, UUID> assigned = new HashMap<>();
        for (List<PutawayTaskView> lot : byLot.values()) {
            for (PutawayTaskView task : lot) {
                List<PutawayRanker.Suggestion> bins = putaway.suggest(warehouseId, task, lotOf.get(task.id()),
                        reserved);
                if (bins.isEmpty()) {
                    if (overflow != null) {
                        assigned.put(task.id(), overflow);
                    }
                    continue;
                }
                String code = bins.get(0).locationCode();
                assigned.put(task.id(), binIds.get(code));
                CartonLoad load = loads.get(task.stockPackageId());
                if (load != null) {
                    reserved.add(new Reserved(code, load.sku(), lotOf.get(task.id()), load.units(), load.volumeM3(),
                            load.weightKg()));
                }
            }
        }
        return assigned;
    }

    /** The first usable OVERFLOW area of the warehouse by code; null when it has none. */
    private UUID overflowArea(UUID warehouseId) {
        return warehouses.areasOf(warehouseId, StorageLocationView.AreaType.OVERFLOW).stream()
                .filter(StorageLocationView::usable)
                .min(Comparator.comparing(StorageLocationView::locationCode))
                .map(StorageLocationView::id).orElse(null);
    }

    /**
     * Puts a carton of the cart into the scanned bin (05 §4.5 step 15): the assigned one or any other that takes it.
     * The trip is {@code COMPLETED} with its last carton.
     *
     * @throws BusinessException {@code PUTAWAY_TRIP_NOT_YOURS}; {@code PUTAWAY_TRIP_INVALID_STATE} before the cart
     *         left; {@code PUTAWAY_CARTON_NOT_ON_TRIP}; and every refusal of {@link PutawayService#confirm}
     */
    @Transactional
    public TripView place(UUID tripId, String locationCode, String lpn, UUID actorId) {
        PutawayTripView trip = own(trips.lockForUpdate(tripId), actorId);
        if (!"IN_PROGRESS".equals(trip.status())) {
            throw new BusinessException(ErrorCode.PUTAWAY_TRIP_INVALID_STATE,
                    "Putaway trip %s is %s; set off before putting cartons away".formatted(tripId, trip.status()));
        }
        PutawayTaskView task = taskOnCart(tripId, lpn);
        putaway.confirm(task.id(), locationCode, lpn, actorId);
        return view(trips.find(tripId).orElseThrow());
    }

    /**
     * Gives a carton on its way another bin (05 §4.5 step 15: the assigned one was refused when scanned): the best the
     * ranking offers now, the bin it had left out; the OVERFLOW area when no bin takes it.
     */
    @Transactional
    public TripView reassign(UUID tripId, String lpn, UUID actorId) {
        PutawayTripView trip = own(trips.lockForUpdate(tripId), actorId);
        PutawayTaskView task = taskOnCart(tripId, lpn);
        UUID current = trip.onCart(task.id()).map(PutawayTripView.Carton::assignedLocationId).orElse(null);
        String currentCode = current == null ? null
                : warehouses.findLocation(current).map(StorageLocationView::locationCode).orElse(null);
        Map<String, UUID> binIds = warehouses.putawayCandidates(trip.warehouseId()).stream()
                .collect(Collectors.toMap(c -> c.locationCode(), c -> c.locationId(), (a, b) -> a));
        UUID next = putaway.suggest(trip.warehouseId(), task, putaway.lotOfTask(task),
                        putaway.heldByTrips(trip.warehouseId(), task.id())).stream()
                .map(PutawayRanker.Suggestion::locationCode)
                .filter(code -> !code.equals(currentCode))
                .findFirst().map(binIds::get)
                .orElseGet(() -> overflowArea(trip.warehouseId()));
        return view(trips.reassign(tripId, task.id(), next));
    }

    /**
     * Ends the trip (05 §4.5 step 17): what is still on the cart goes back, its tasks pending.
     *
     * @param forAnother a warehouse manager closing a clerk's trip; otherwise only the trip's own clerk
     */
    @Transactional
    public TripView close(UUID tripId, UUID actorId, boolean forAnother) {
        PutawayTripView trip = trips.lockForUpdate(tripId);
        WarehouseScope.requireWarehouse(trip.warehouseId());
        if (!forAnother) {
            own(trip, actorId);
        }
        return view(trips.close(tripId, actorId));
    }

    /** The trip, for its own clerk still assigned to its warehouse; anybody else is refused. */
    private static PutawayTripView own(PutawayTripView trip, UUID actorId) {
        WarehouseScope.requireWarehouse(trip.warehouseId());
        if (!trip.operatorId().equals(actorId)) {
            throw new BusinessException(ErrorCode.PUTAWAY_TRIP_NOT_YOURS,
                    "Putaway trip " + trip.id() + " belongs to another clerk");
        }
        return trip;
    }

    private StockPackage carton(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        return (normal == null ? Optional.<StockPackage>empty() : stockPackages.findByLpn(normal))
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton " + lpn));
    }

    /** The task of the scanned carton, which must be on this trip's cart. */
    private PutawayTaskView taskOnCart(UUID tripId, String lpn) {
        StockPackage carton = carton(lpn);
        PutawayTripView trip = trips.find(tripId).orElseThrow();
        return tasks.openTaskOf(carton.id().value())
                .filter(task -> trip.onCart(task.id()).isPresent())
                .orElseThrow(() -> new BusinessException(ErrorCode.PUTAWAY_CARTON_NOT_ON_TRIP,
                        "Carton %s is not on the cart of this trip".formatted(carton.lpn())));
    }

    private Map<UUID, StockPackage> cartonsOf(java.util.Collection<PutawayTaskView> found) {
        return stockPackages.findAllById(found.stream().map(PutawayTaskView::stockPackageId)
                        .filter(java.util.Objects::nonNull).distinct().map(StockPackageId::new).toList()).stream()
                .collect(Collectors.toMap(c -> c.id().value(), Function.identity()));
    }

    private TripView view(PutawayTripView trip) {
        Map<UUID, PutawayTaskView> taskOf = new HashMap<>();
        trip.cartons().forEach(c -> tasks.find(c.putawayTaskId()).ifPresent(task -> taskOf.put(task.id(), task)));
        Map<UUID, StockPackage> cartonOf = cartonsOf(taskOf.values());
        Map<UUID, CartonLoad> loads = occupancy.loadsOf(cartonOf.keySet());
        Map<UUID, String> codeOf = new HashMap<>();
        Function<UUID, String> code = id -> id == null ? null : codeOf.computeIfAbsent(id,
                location -> warehouses.findLocation(location).map(StorageLocationView::locationCode).orElse(null));

        List<CartonView> cartons = trip.cartons().stream().map(c -> {
            PutawayTaskView task = taskOf.get(c.putawayTaskId());
            StockPackage carton = task == null ? null : cartonOf.get(task.stockPackageId());
            CartonLoad load = carton == null ? null : loads.get(carton.id().value());
            return new CartonView(c.putawayTaskId(), carton == null ? null : carton.lpn(),
                    task == null ? null : task.sku(), load == null ? null : load.lotNumber(), c.status(),
                    code.apply(c.assignedLocationId()), c.stopSequence(), c.holding(), c.loadedAt(), c.placedAt());
        }).toList();
        List<StopView> stops = trip.stops().stream().map(stop -> new StopView(stop.sequence(), stop.locationCode(),
                stop.x(), stop.y(), cartons.stream()
                .filter(c -> "ON_CART".equals(c.status()) && stop.locationCode().equals(c.assignedLocationCode()))
                .map(CartonView::lpn).toList())).toList();
        return new TripView(trip.id(), trip.warehouseId(), trip.operatorId(), trip.status(),
                code.apply(trip.startLocationId()), trip.startX(), trip.startY(), trip.startedAt(),
                trip.lastActivityAt(), trip.closedAt(), trip.closedBy(), cartons, stops);
    }
}
