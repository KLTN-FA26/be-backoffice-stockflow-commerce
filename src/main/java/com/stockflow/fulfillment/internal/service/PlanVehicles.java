package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadPlanJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanStore;
import com.stockflow.fulfillment.internal.repository.LoadPlanVehicleJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The load plan behind a full-truckload dispatch load (SCRUM-392; kltn-docs 09 §4.2 step 5, BR-14, BR-15): which plan
 * and vehicle a load is, where the plan placed a parcel, the live load of each vehicle, and the plan turning DISPATCHED
 * once every vehicle has left. Lock order, as in {@code LoadPlanService}: the plan's row before any load's row.
 */
@Component
class PlanVehicles {

    private static final Logger log = LoggerFactory.getLogger(PlanVehicles.class);

    /** A plan's vehicle: the plan's row (locked or read), the plan, and the vehicle. */
    record PlanOfLoad(LoadPlanJpaEntity header, LoadPlan plan, LoadPlan.Vehicle vehicle) {
        Set<UUID> placedHere() {
            return vehicle == null ? Set.of() : vehicle.placements().stream().map(LoadPlan.Placed::packageId)
                    .collect(Collectors.toSet());
        }
    }

    private final LoadPlanJpaRepository plans;
    private final LoadPlanStore planStore;
    private final LoadPlanVehicleJpaRepository vehicles;
    private final LoadPlanPackageJpaRepository planMembers;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository loadMembers;
    private final DispatchLoadPackageJpaRepository loaded;
    private final DispatchLoadStore loadStore;

    @SuppressWarnings("java:S107")
    PlanVehicles(LoadPlanJpaRepository plans, LoadPlanStore planStore, LoadPlanVehicleJpaRepository vehicles,
                 LoadPlanPackageJpaRepository planMembers, DispatchLoadJpaRepository loads,
                 DispatchLoadShipmentJpaRepository loadMembers, DispatchLoadPackageJpaRepository loaded,
                 DispatchLoadStore loadStore) {
        this.plans = plans;
        this.planStore = planStore;
        this.vehicles = vehicles;
        this.planMembers = planMembers;
        this.loads = loads;
        this.loadMembers = loadMembers;
        this.loaded = loaded;
        this.loadStore = loadStore;
    }

    /** The plan of a load plan's vehicle, its row locked (taken before the vehicle's load's own lock). */
    PlanOfLoad lock(UUID loadPlanVehicleId) {
        LoadPlanJpaEntity header = plans.lockById(planIdOf(loadPlanVehicleId))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        return of(header, loadPlanVehicleId);
    }

    /** The plan of a full-truckload load, read. */
    PlanOfLoad read(DispatchLoadJpaEntity load) {
        LoadPlanJpaEntity header = plans.findById(planIdOf(load.getLoadPlanVehicleId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        return of(header, load.getLoadPlanVehicleId());
    }

    private PlanOfLoad of(LoadPlanJpaEntity header, UUID loadPlanVehicleId) {
        LoadPlan plan = planStore.load(header);
        LoadPlan.Vehicle vehicle = plan.vehicles().stream().filter(v -> v.id().equals(loadPlanVehicleId))
                .findFirst().orElse(null);
        return new PlanOfLoad(header, plan, vehicle);
    }

    private UUID planIdOf(UUID loadPlanVehicleId) {
        return vehicles.findById(loadPlanVehicleId).map(LoadPlanVehicleJpaEntity::getLoadPlanId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_PLAN_VEHICLE_NOT_FOUND));
    }

    /** LP2-1: only a confirmed plan's vehicles are booked and loaded. */
    static void requireConfirmed(PlanOfLoad ctx) {
        if (ctx.plan().status() != LoadPlanStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_PLAN_TRANSITION, ("Load plan %s is %s; only the "
                    + "vehicles of a confirmed plan are booked, loaded and handed over").formatted(ctx.plan().code(),
                    ctx.plan().status()));
        }
    }

    /** The live (not cancelled) load of each active vehicle of the plan, by vehicle id. */
    Map<UUID, DispatchLoadJpaEntity> liveLoads(LoadPlan plan) {
        List<UUID> ids = plan.activeVehicles().stream().map(LoadPlan.Vehicle::id).toList();
        Map<UUID, DispatchLoadJpaEntity> out = new HashMap<>();
        if (!ids.isEmpty()) {
            loads.findByLoadPlanVehicleIdIn(ids).stream().filter(l -> l.getStatus() != LoadStatus.CANCELLED)
                    .forEach(l -> out.put(l.getLoadPlanVehicleId(), l));
        }
        return out;
    }

    /** Where the load's plan placed {@code packageId} (09 edge "báo kiện thuộc xe / kế hoạch nào"). */
    DispatchLoad.Placement placement(PlanOfLoad ctx, UUID packageId) {
        for (LoadPlan.Vehicle v : ctx.plan().activeVehicles()) {
            if (v.placements().stream().anyMatch(p -> p.packageId().equals(packageId))) {
                boolean here = ctx.vehicle() != null && v.id().equals(ctx.vehicle().id());
                DispatchLoadJpaEntity live = here ? null : liveLoads(ctx.plan()).get(v.id());
                return new DispatchLoad.Placement(here, ctx.plan().code(), v.seq(),
                        live == null ? null : live.getCode());
            }
        }
        String held = planMembers.findByPackageIdInAndActiveTrue(List.of(packageId)).stream().findFirst()
                .map(LoadPlanPackageJpaEntity::getLoadPlanId).flatMap(plans::findById).map(LoadPlanJpaEntity::getCode)
                .orElse(null);
        return new DispatchLoad.Placement(false, held, null, null);
    }

    /**
     * BR-15: every vehicle of the plan that still carries something was loaded by its plan, signed for and left -
     * the plan is DISPATCHED. Vehicles whose load was cancelled (emptied by cancelled orders) do not count; at least
     * one must have left. The plan's row must be locked by the caller.
     *
     * @return whether the plan turned DISPATCHED
     */
    boolean completeIfDispatched(LoadPlanJpaEntity header, LoadPlan plan, Instant at) {
        if (plan.status() != LoadPlanStatus.CONFIRMED) {
            return false;
        }
        Map<UUID, DispatchLoadJpaEntity> live = liveLoads(plan);
        boolean anyLeft = false;
        for (LoadPlan.Vehicle v : plan.activeVehicles()) {
            DispatchLoadJpaEntity load = live.get(v.id());
            if (load == null) {
                continue;
            }
            if (load.getStatus() != LoadStatus.DISPATCHED) {
                return false;
            }
            anyLeft = true;
        }
        if (!anyLeft) {
            return false;
        }
        plan.markDispatched(at);
        planStore.save(plan, header, at);
        log.info("Load plan {} dispatched: every vehicle left (BR-15)", plan.code());
        return true;
    }

    /**
     * Whether a parcel of the shipment already left on a signed vehicle (a full truckload spanning vehicles, LP-3):
     * its order's cancellation can no longer void it.
     */
    boolean partlyGone(UUID shipmentId) {
        Set<UUID> loadIds = loaded.findByShipmentIdInAndActiveTrue(List.of(shipmentId)).stream()
                .map(DispatchLoadPackageJpaEntity::getLoadId).collect(Collectors.toSet());
        return !loadIds.isEmpty() && loads.findByIdIn(loadIds).stream()
                .anyMatch(l -> l.getStatus() == LoadStatus.DISPATCHED);
    }

    /**
     * The shipment's order was cancelled (08 BR-05): off every open manifest it is on, in load id order. On a full
     * truckload being loaded, its parcels on board are to be unloaded first; a vehicle of a plan left with nothing to
     * carry is called off, and the plan is DISPATCHED if every other vehicle left (BR-15). The plans' rows are locked
     * by the caller before this ({@code LoadPlanReleases.releaseShipment}).
     */
    void withdraw(UUID shipmentId, Instant at) {
        List<UUID> loadIds = loadMembers.findByShipmentIdInAndActiveTrue(List.of(shipmentId)).stream()
                .map(DispatchLoadShipmentJpaEntity::getLoadId).distinct().sorted().toList();
        Set<UUID> touchedPlans = new HashSet<>();
        for (UUID loadId : loadIds) {
            loads.lockById(loadId).ifPresent(header -> {
                DispatchLoad load = loadStore.load(header);
                if (!load.withdrawShipment(shipmentId, "The order was cancelled", at)) {
                    return;
                }
                if (callOffIfEmpty(load, "Nothing left to carry: the order(s) on it were cancelled", at)) {
                    touchedPlans.add(header.getLoadPlanVehicleId());
                }
                loadStore.save(load, header);
                log.info("Shipment {} of a cancelled order taken off load {}{}", shipmentId, load.code(),
                        load.pendingUnloads().isEmpty() ? "" : " - " + load.pendingUnloads().size()
                                + " parcel(s) to unload");
            });
        }
        completePlansOf(touchedPlans, at);
    }

    /**
     * A full truckload with no shipment left on its manifest and nothing left to unload is called off (it does not hold
     * the plan's BR-15 back). Parcel loads are left to the dispatcher.
     *
     * @return whether it was called off
     */
    static boolean callOffIfEmpty(DispatchLoad load, String reason, Instant at) {
        return load.mode() == DeliveryMode.FULL_TRUCKLOAD && load.status().isOpen() && load.activeShipments().isEmpty()
                && load.pendingUnloads().isEmpty() && load.cancel(reason, null, at);
    }

    /** BR-15 for the plans of these vehicles (their rows are locked first, in id order). */
    void completePlansOf(Collection<UUID> vehicleIds, Instant at) {
        if (vehicleIds.isEmpty()) {
            return;
        }
        vehicles.findAllById(vehicleIds).stream().map(LoadPlanVehicleJpaEntity::getLoadPlanId).distinct()
                .sorted(Comparator.naturalOrder()).forEach(planId -> plans.lockById(planId).ifPresent(header ->
                        completeIfDispatched(header, planStore.load(header), at)));
    }

    /** A load's vehicle in a plan, as lists show it. */
    record VehicleRef(UUID loadPlanId, String loadPlanCode, int seq, String vehicleCode) { }

    /** The plan and seq of each of these vehicles (for lists and details), by vehicle id. */
    Map<UUID, VehicleRef> describe(Collection<UUID> vehicleIds) {
        Map<UUID, VehicleRef> out = new HashMap<>();
        if (vehicleIds.isEmpty()) {
            return out;
        }
        List<LoadPlanVehicleJpaEntity> rows = vehicles.findAllById(vehicleIds);
        Map<UUID, String> codes = plans.findByIdIn(rows.stream().map(LoadPlanVehicleJpaEntity::getLoadPlanId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(LoadPlanJpaEntity::getId,
                LoadPlanJpaEntity::getCode));
        rows.forEach(v -> out.put(v.getId(), new VehicleRef(v.getLoadPlanId(), codes.get(v.getLoadPlanId()), v.getSeq(),
                v.getVehicleCode())));
        return out;
    }
}
