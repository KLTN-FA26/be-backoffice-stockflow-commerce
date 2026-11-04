package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanReleaseReason;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.order.api.OrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 09 §4.2 step 3 / edge "kiện không xếp được ... để lại khu DISPATCH cho chuyến sau (giao lệch chuyến theo chính sách
 * split shipment)" (SCRUM-392, review of #162): a shipment with parcels placed in a plan and parcels left behind at its
 * confirmation is split when the plan's first vehicle starts loading - BR-14's point of no return, so a plan reopened or
 * cancelled before it leaves its shipments untouched. The left-behind parcels move to a new shipment waiting for
 * dispatch, free for a later trip; their labels - and those of the parcels that stay, whose n/N changed - are marked
 * outdated and are reprinted before they are loaded. Until then a shipment partly held by a plan is not planned elsewhere (LP-5).
 * LP-4 already refused this for an order that does not allow partial shipments. Idempotent: a parcel already moved is
 * not moved again.
 */
@Component
class LeftBehindParcels {

    private static final Logger log = LoggerFactory.getLogger(LeftBehindParcels.class);

    private final ShipmentJpaRepository shipments;
    private final ShipmentLocks locks;
    private final PackageJpaRepository packages;
    private final ShipmentTimeline timeline;
    private final LoadPlanParcels parcels;

    LeftBehindParcels(ShipmentJpaRepository shipments, ShipmentLocks locks, PackageJpaRepository packages,
                      ShipmentTimeline timeline, LoadPlanParcels parcels) {
        this.shipments = shipments;
        this.locks = locks;
        this.packages = packages;
        this.timeline = timeline;
        this.parcels = parcels;
    }

    /** Splits the left-behind parcels of the plan's leaving shipments off; the plan's row is locked by the caller. */
    void splitOff(LoadPlan plan, UUID by, Instant at) {
        Set<UUID> placed = plan.placedPackageIds();
        Map<UUID, List<UUID>> leftBehind = new TreeMap<>();
        Set<UUID> leaving = new HashSet<>();
        for (LoadPlan.Member m : plan.members()) {
            if (m.active() && placed.contains(m.packageId())) {
                leaving.add(m.shipmentId());
            } else if (m.releaseReason() == LoadPlanReleaseReason.LEFT_BEHIND) {
                leftBehind.computeIfAbsent(m.shipmentId(), k -> new ArrayList<>()).add(m.packageId());
            }
        }
        leftBehind.keySet().retainAll(leaving);
        if (leftBehind.isEmpty()) {
            return;
        }
        Map<UUID, ShipmentJpaEntity> rows = locks.lock(leftBehind.keySet()).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        leftBehind.forEach((shipmentId, ids) -> {
            ShipmentJpaEntity row = rows.get(shipmentId);
            List<PackageJpaEntity> ofShipment = packages.findByShipmentIdOrderByPackageNoAsc(shipmentId).stream()
                    .filter(p -> p.getStatus() == PackageStatus.SEALED).toList();
            List<PackageJpaEntity> moving = ofShipment.stream().filter(p -> ids.contains(p.getId())).toList();
            int staying = ofShipment.size() - moving.size();
            if (moving.isEmpty() || staying < 1) {
                return;
            }
            OrderSummary order = parcels.ordersOf(List.of(row)).get(row.getOrderId());
            String orderNumber = order != null ? order.orderNumber() : row.getCode().replaceFirst("-S\\d+$", "");
            Shipment stays = row.toDomain();
            Shipment rest = stays.splitOff(Identifiers.newId(),
                    orderNumber + "-S" + (shipments.countByOrderId(row.getOrderId()) + 1), Identifiers.newId(),
                    moving.size(), staying, "Left behind by load plan %s for a later trip".formatted(plan.code()), by,
                    at);
            row.apply(stays);
            shipments.saveAndFlush(row);
            shipments.saveAndFlush(ShipmentJpaEntity.of(rest));
            timeline.record(rest, null, order, orderNumber, null, null);
            for (PackageJpaEntity parcel : moving) {
                parcel.apply(rest.id(), parcel.getPackageType(), parcel.getStatus(), parcel.getWeightKg(),
                        parcel.getLengthCm(), parcel.getWidthCm(), parcel.getHeightCm(), parcel.getDimWeightKg(),
                        parcel.isRotatable(), parcel.isRotatableOverridden(), parcel.isFragile(),
                        parcel.isFragileOverridden(), parcel.getSealedAt(), parcel.getSealedBy(), parcel.getVoidedAt(),
                        parcel.getVoidedBy());
                parcel.markLabelOutdated(at);
                packages.save(parcel);
            }
            // The parcels that stay are now n of a smaller N: their labels are reprinted too (review of #162).
            ofShipment.stream().filter(p -> !ids.contains(p.getId())).forEach(parcel -> {
                parcel.markLabelOutdated(at);
                packages.save(parcel);
            });
            packages.flush();
            log.info("Load plan {}: {} left-behind parcel(s) of shipment {} split off as shipment {} (labels to reprint)",
                    plan.code(), moving.size(), row.getCode(), rest.code());
        });
    }
}
