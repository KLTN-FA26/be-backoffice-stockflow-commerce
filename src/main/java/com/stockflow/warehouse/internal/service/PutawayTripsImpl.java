package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.api.PutawayHold;
import com.stockflow.warehouse.api.PutawayTripView;
import com.stockflow.warehouse.api.PutawayTrips;
import com.stockflow.warehouse.internal.domain.PutawayRoute;
import com.stockflow.warehouse.internal.domain.PutawayStatus;
import com.stockflow.warehouse.internal.domain.PutawayTask;
import com.stockflow.warehouse.internal.domain.PutawayTaskRepository;
import com.stockflow.warehouse.internal.domain.PutawayTrip;
import com.stockflow.warehouse.internal.domain.PutawayTripItemStatus;
import com.stockflow.warehouse.internal.domain.PutawayTripRepository;
import com.stockflow.warehouse.internal.domain.PutawayTripStatus;
import com.stockflow.warehouse.internal.repository.PutawayStopReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only implementation of {@link PutawayTrips}. Joins the caller's transaction (inventory's putaway trip), so the
 * cart, the holds and the stock move commit or roll back together.
 *
 * <p>The route is ordered here because the map is this module's: the caller chooses a bin for each carton, this class
 * turns the bins into stops ({@link PutawayRoute}) and numbers them. The stand points are read from the map each time
 * and never stored (kltn-docs 06 §4.4 step 6).</p>
 *
 * <p>The stops still to visit are ordered from where the clerk is ({@link #origins}): the area the cart left from
 * until a carton is put away, the place of the last carton put away after that. Ordering them from the start area
 * every time would send a clerk standing at the first stop to whatever is nearest to the door they left behind.</p>
 *
 * <p>Package-private class, public interface: other modules inject it by the interface.</p>
 */
@Service
@Transactional
class PutawayTripsImpl implements PutawayTrips {

    private final PutawayTripRepository trips;
    private final PutawayTaskRepository tasks;
    private final PutawayStopReader stopReader;
    private final Clock clock;

    PutawayTripsImpl(PutawayTripRepository trips, PutawayTaskRepository tasks, PutawayStopReader stopReader,
                     Clock clock) {
        this.trips = trips;
        this.tasks = tasks;
        this.stopReader = stopReader;
        this.clock = clock;
    }

    @Override
    public PutawayTripView open(UUID warehouseId, UUID operatorId) {
        // uk_putaway_trip_open_operator decides between two requests at once; this is the answer a person reads.
        trips.findOpenByOperator(operatorId).ifPresent(open -> {
            throw new BusinessException(ErrorCode.PUTAWAY_TRIP_ALREADY_OPEN,
                    "You already have putaway trip %s open; finish or close it first".formatted(open.id()),
                    List.of(), Map.of("tripId", open.id().toString()));
        });
        return toView(trips.save(PutawayTrip.open(Identifiers.newId(), warehouseId, operatorId, clock.instant())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PutawayTripView> find(UUID tripId) {
        return trips.findById(tripId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PutawayTripView> openTripOf(UUID operatorId) {
        return trips.findOpenByOperator(operatorId).map(this::toView);
    }

    @Override
    public PutawayTripView lockForUpdate(UUID tripId) {
        return toView(locked(tripId));
    }

    @Override
    public Optional<PutawayTripView> lockTripCarrying(UUID putawayTaskId) {
        return trips.findOpenTripCarrying(putawayTaskId).flatMap(trips::findByIdForUpdate).map(this::toView);
    }

    @Override
    public PutawayTripView load(UUID tripId, UUID putawayTaskId) {
        PutawayTrip trip = locked(tripId);
        PutawayTask task = tasks.findById(putawayTaskId).orElseThrow(() -> new BusinessException(
                ErrorCode.PUTAWAY_TASK_NOT_FOUND, "Putaway task " + putawayTaskId + " not found"));
        if (task.status() != PutawayStatus.PENDING) {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_DONE,
                    "Putaway task %s is %s; only a pending carton goes onto a cart".formatted(task.id(), task.status()));
        }
        // uk_putaway_trip_item_on_cart decides between two scans at once; this is the answer a person reads.
        trips.findOpenTripCarrying(putawayTaskId).filter(other -> !other.equals(tripId)).ifPresent(other -> {
            throw new BusinessException(ErrorCode.PUTAWAY_TASK_ON_ANOTHER_TRIP,
                    "That carton is on the cart of another putaway trip", List.of(),
                    Map.of("tripId", other.toString()));
        });
        trip.load(Identifiers.newId(), putawayTaskId, clock.instant());
        return toView(trips.save(trip));
    }

    @Override
    public PutawayTripView unload(UUID tripId, UUID putawayTaskId) {
        PutawayTrip trip = locked(tripId);
        trip.unload(putawayTaskId, clock.instant());
        return toView(trips.save(trip));
    }

    @Override
    public PutawayTripView start(UUID tripId, Map<UUID, UUID> assignedLocationByTask, UUID startLocationId) {
        PutawayTrip trip = locked(tripId);
        trip.start(assignedLocationByTask, stopNumbers(assignedLocationByTask, Arrays.asList(startLocationId)),
                startLocationId, clock.instant());
        return toView(trips.save(trip));
    }

    @Override
    public PutawayTripView reassign(UUID tripId, UUID putawayTaskId, UUID locationId) {
        PutawayTrip trip = locked(tripId);
        trip.reassign(putawayTaskId, locationId, clock.instant());
        renumber(trip);
        return toView(trips.save(trip));
    }

    @Override
    public void taskPutAway(UUID putawayTaskId) {
        trips.findOpenTripCarrying(putawayTaskId).flatMap(trips::findByIdForUpdate).ifPresent(trip -> {
            if (!trip.carries(putawayTaskId)) {
                return;
            }
            Instant now = clock.instant();
            if (trip.status() == PutawayTripStatus.LOADING) {
                // Put away one by one before the cart left: the carton is simply no longer on it.
                trip.unload(putawayTaskId, now);
            } else {
                trip.recordPlaced(putawayTaskId, now);
                if (trip.status() == PutawayTripStatus.IN_PROGRESS) {
                    renumber(trip);
                }
            }
            trips.save(trip);
        });
    }

    @Override
    public PutawayTripView close(UUID tripId, UUID closedBy) {
        PutawayTrip trip = locked(tripId);
        trip.close(closedBy, clock.instant());
        return toView(trips.save(trip));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PutawayHold> holds(UUID warehouseId, UUID exceptTripId) {
        return trips.findHolds(warehouseId, exceptTripId).stream()
                .map(hold -> new PutawayHold(hold.tripId(), hold.putawayTaskId(), hold.stockPackageId(),
                        hold.locationId())).toList();
    }

    private PutawayTrip locked(UUID tripId) {
        return trips.findByIdForUpdate(tripId).orElseThrow(() -> new BusinessException(
                ErrorCode.PUTAWAY_TRIP_NOT_FOUND, "Putaway trip " + tripId + " not found"));
    }

    /** The stops of the cartons still on the cart, ordered again from where the clerk is. */
    private void renumber(PutawayTrip trip) {
        Map<UUID, UUID> assigned = new HashMap<>();
        trip.items().stream()
                .filter(item -> item.status() == PutawayTripItemStatus.ON_CART && item.assignedLocationId() != null)
                .forEach(item -> assigned.put(item.taskId(), item.assignedLocationId()));
        trip.renumberStops(stopNumbers(assigned, origins(trip)));
    }

    /**
     * Where the stops still to visit are measured from, best first: the location the last carton of the trip was
     * really put into - the task's, which may be another bin than the one assigned (05 BR-07) - then the area the
     * cart left from. The second is there for a first one the map cannot place.
     */
    private List<UUID> origins(PutawayTrip trip) {
        List<UUID> origins = new ArrayList<>();
        trip.items().stream()
                .filter(item -> item.status() == PutawayTripItemStatus.PLACED && item.placedAt() != null)
                .max(Comparator.comparing(PutawayTrip.Item::placedAt).thenComparing(PutawayTrip.Item::id))
                .flatMap(item -> tasks.findById(item.taskId()))
                .map(PutawayTask::targetLocationId)
                .ifPresent(origins::add);
        origins.add(trip.startLocationId());
        return origins;
    }

    /** The stop number of each task: nearest neighbour from the origin, cartons for one bin sharing a stop. */
    private Map<UUID, Integer> stopNumbers(Map<UUID, UUID> assignedLocationByTask, List<UUID> origins) {
        List<PutawayRoute.Stop> route = route(new LinkedHashSet<>(assignedLocationByTask.values()), origins);
        Map<UUID, Integer> numberOfLocation = new HashMap<>();
        for (int i = 0; i < route.size(); i++) {
            numberOfLocation.put(route.get(i).locationId(), i + 1);
        }
        Map<UUID, Integer> stopByTask = new HashMap<>();
        assignedLocationByTask.forEach((task, location) -> {
            if (location != null && numberOfLocation.containsKey(location)) {
                stopByTask.put(task, numberOfLocation.get(location));
            }
        });
        return stopByTask;
    }

    /**
     * @param origins where the route may start, best first (nulls allowed): the first one the map can place is used;
     *                with none, the route begins at the first stop by code
     */
    private List<PutawayRoute.Stop> route(Set<UUID> locationIds, List<UUID> origins) {
        locationIds.remove(null);
        Set<UUID> wanted = new LinkedHashSet<>(locationIds);
        origins.stream().filter(Objects::nonNull).forEach(wanted::add);
        Map<UUID, PutawayRoute.Stop> known = stopReader.stopsOf(wanted);
        PutawayRoute.Point start = origins.stream().filter(Objects::nonNull).map(known::get)
                .filter(stop -> stop != null && stop.point() != null)
                .map(PutawayRoute.Stop::point).findFirst().orElse(null);
        List<PutawayRoute.Stop> stops = new ArrayList<>();
        for (UUID location : locationIds) {
            // A location the map does not know still gets a stop: after every one it can place.
            stops.add(known.getOrDefault(location, new PutawayRoute.Stop(location, location.toString(), null, false)));
        }
        return PutawayRoute.order(start, stops);
    }

    private PutawayTripView toView(PutawayTrip trip) {
        List<PutawayTripView.Carton> cartons = trip.items().stream()
                .map(item -> new PutawayTripView.Carton(item.taskId(), item.status().name(),
                        item.assignedLocationId(), item.stopSequence(), item.holding(), item.loadedAt(),
                        item.placedAt())).toList();
        List<PutawayTripView.Stop> stops = List.of();
        PutawayRoute.Point start = null;
        if (trip.status() == PutawayTripStatus.IN_PROGRESS) {
            Set<UUID> remaining = new LinkedHashSet<>();
            trip.items().stream()
                    .filter(item -> item.status() == PutawayTripItemStatus.ON_CART && item.assignedLocationId() != null)
                    .forEach(item -> remaining.add(item.assignedLocationId()));
            List<PutawayRoute.Stop> route = route(remaining, origins(trip));
            List<PutawayTripView.Stop> numbered = new ArrayList<>();
            for (int i = 0; i < route.size(); i++) {
                PutawayRoute.Stop stop = route.get(i);
                numbered.add(new PutawayTripView.Stop(i + 1, stop.locationId(), stop.locationCode(),
                        stop.point() == null ? null : stop.point().x(), stop.point() == null ? null : stop.point().y()));
            }
            stops = numbered;
            if (trip.startLocationId() != null) {
                PutawayRoute.Stop from = stopReader.stopsOf(List.of(trip.startLocationId()))
                        .get(trip.startLocationId());
                start = from == null ? null : from.point();
            }
        }
        return new PutawayTripView(trip.id(), trip.warehouseId(), trip.operatorId(), trip.status().name(),
                trip.startLocationId(), start == null ? null : start.x(), start == null ? null : start.y(),
                trip.startedAt(), trip.lastActivityAt(), trip.closedAt(), trip.closedBy(), cartons, stops);
    }
}
