package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link ShipmentCancellations}. The shipment itself - its lock, the vehicles it must be off, its status and
 * timeline - is here; its cartons, the check session and the order's hold are {@link CartonCheckService}'s.
 *
 * <p><b>Lock order</b>, as everywhere in fulfillment: the pick first, then the shipment's row ({@link ShipmentLocks},
 * read again once locked), then the order (taken by the hold). The handover locks shipments, then orders, and no path
 * locks a shipment before a pick, so none of these waits in a circle. Nothing is locked on a load plan or a load: a
 * shipment held by either is refused, not taken out.</p>
 */
@Service
@Transactional
class ShipmentCancellationService implements ShipmentCancellations {

    private static final Logger log = LoggerFactory.getLogger(ShipmentCancellationService.class);
    private static final int MAX_REASON = 500;

    private final ShipmentJpaRepository shipments;
    private final ShipmentLocks locks;
    private final PackJpaRepository packs;
    private final PickJpaRepository picks;
    private final PackageJpaRepository packages;
    private final LoadPlanReleases loadPlans;
    private final DispatchLoadShipmentJpaRepository loadMembers;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadStore loadStore;
    private final OrderService orders;
    private final CartonCheckService cartonChecks;
    private final ShipmentTimeline timeline;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    ShipmentCancellationService(ShipmentJpaRepository shipments, ShipmentLocks locks, PackJpaRepository packs,
                                PickJpaRepository picks, PackageJpaRepository packages, LoadPlanReleases loadPlans,
                                DispatchLoadShipmentJpaRepository loadMembers, DispatchLoadJpaRepository loads,
                                DispatchLoadStore loadStore, OrderService orders, CartonCheckService cartonChecks,
                                ShipmentTimeline timeline, Clock clock) {
        this.shipments = shipments;
        this.locks = locks;
        this.packs = packs;
        this.picks = picks;
        this.packages = packages;
        this.loadPlans = loadPlans;
        this.loadMembers = loadMembers;
        this.loads = loads;
        this.loadStore = loadStore;
        this.orders = orders;
        this.cartonChecks = cartonChecks;
        this.timeline = timeline;
        this.clock = clock;
    }

    @Override
    public UUID cancel(UUID shipmentId, Cancellation cancellation, CurrentUser actor) {
        String reason = reason(cancellation.reason());
        Set<UUID> damaged = damaged(cancellation.damagedPackageIds());
        ShipmentJpaEntity found = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
        WarehouseScope.requireWarehouse(found.getWarehouseId());
        if (found.getPackId() == null) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s was not made at the check "
                    + "station; it has no cartons to take back").formatted(found.getCode()));
        }
        PackJpaEntity pack = packs.findById(found.getPackId())
                .orElseThrow(() -> new IllegalStateException("Shipment %s names check session %s, which is gone"
                        .formatted(found.getCode(), found.getPackId())));
        PickJpaEntity pick = picks.lockById(pack.getPickId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        ShipmentJpaEntity row = locks.lock(List.of(shipmentId)).getFirst();
        Shipment shipment = row.toDomain();
        if (shipment.status() != ShipmentStatus.READY_TO_DISPATCH) {
            throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, ("Shipment %s is %s; only one waiting for "
                    + "dispatch is cancelled").formatted(row.getCode(), shipment.status()));
        }
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        List<UUID> parcels = packages.findByShipmentIdOrderByPackageNoAsc(shipmentId).stream()
                .map(PackageJpaEntity::getId).toList();
        requireNotDamagedElsewhere(row, damaged, parcels);
        requireOffTheVehicles(row, parcels);

        Instant now = clock.instant();
        shipment.cancel(reason, actor.userId(), now);
        row.apply(shipment);
        shipments.saveAndFlush(row);
        cartonChecks.removeCancelledShipment(pick, pack, order, row, damaged, reason, actor);
        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, order, null, null, null);
        log.info("Shipment {} of order {} cancelled before dispatch by {}: {} carton(s), {} damaged", row.getCode(),
                order.orderNumber(), actor.userId(), parcels.size(), damaged.size());
        return row.getId();
    }

    /**
     * 09 §5.1: the warehouse manager takes the shipment out of its load plan, or off its load, first. A carton a live
     * plan holds is {@code PACKAGE_IN_LOAD_PLAN} ({@link LoadPlanReleases#requireNotPlanned}); one still to be scanned
     * off a vehicle that was called off is {@code PACKAGE_UNLOAD_PENDING}; a shipment on an open load - which only a
     * confirmed plan makes - is {@code PACKAGE_IN_LOAD_PLAN} too, naming the load.
     */
    private void requireOffTheVehicles(ShipmentJpaEntity row, List<UUID> parcels) {
        loadPlans.requireNotPlanned(parcels);
        Map<UUID, UUID> unloading = loadStore.pendingUnloadLoads(parcels);
        if (!unloading.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_UNLOAD_PENDING, ("Shipment %s has carton(s) still on load %s, "
                    + "which was called off: scan them off first").formatted(row.getCode(), codes(Set.copyOf(
                    unloading.values()))));
        }
        Set<UUID> onLoads = loadMembers.findByShipmentIdInAndActiveTrue(List.of(row.getId())).stream()
                .map(DispatchLoadShipmentJpaEntity::getLoadId).collect(Collectors.toSet());
        if (!onLoads.isEmpty()) {
            throw new BusinessException(ErrorCode.PACKAGE_IN_LOAD_PLAN, ("Shipment %s is on load %s; take it off the "
                    + "load first").formatted(row.getCode(), codes(onLoads)));
        }
    }

    private String codes(Set<UUID> loadIds) {
        return loads.findByIdIn(loadIds).stream().map(DispatchLoadJpaEntity::getCode).sorted()
                .collect(Collectors.joining(", "));
    }

    /** A carton marked damaged must be one of the shipment's (the session says it again, 400 either way). */
    private static void requireNotDamagedElsewhere(ShipmentJpaEntity row, Set<UUID> damaged, List<UUID> parcels) {
        List<FieldError> strangers = damaged.stream().filter(id -> !parcels.contains(id))
                .map(id -> new FieldError("damagedPackageIds", id.toString(), "NotInShipment")).toList();
        if (!strangers.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a carton of shipment %s is marked damaged"
                    .formatted(row.getCode()), strangers);
        }
    }

    private static String reason(String raw) {
        String reason = raw == null ? null : raw.strip();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancelled shipment says why",
                    List.of(FieldError.of("reason", "required, at most 500 characters")));
        }
        return reason;
    }

    private static Set<UUID> damaged(java.util.Collection<UUID> ids) {
        if (ids == null) {
            return Set.of();
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A damaged carton is named by its package id",
                    List.of(FieldError.of("damagedPackageIds", "must not hold an empty id")));
        }
        return new LinkedHashSet<>(ids);
    }
}
