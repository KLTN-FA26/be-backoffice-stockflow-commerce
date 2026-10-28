package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Builders shared by the fulfillment service unit tests. */
final class FulfillmentFixtures {

    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final UUID WAREHOUSE = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    static final UUID OTHER_WAREHOUSE = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

    private FulfillmentFixtures() {
    }

    /** A caller who is not limited to warehouses (ALL scope) and holds no permission: services check those at the edge. */
    static CurrentUser user() {
        return new CurrentUser(UUID.randomUUID(), "dispatcher", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    /** A caller limited to the given warehouses. */
    static CurrentUser userOf(UUID... warehouses) {
        return new CurrentUser(UUID.randomUUID(), "staff", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(), Set.of(warehouses));
    }

    /** A READY_TO_DISPATCH parcel shipment of one parcel, no COD. */
    static Shipment readyShipment() {
        return readyShipment(DeliveryMode.PARCEL, 2, "0");
    }

    static Shipment readyShipment(DeliveryMode mode, int packages, String cod) {
        UUID id = UUID.randomUUID();
        return Shipment.ready(id, "SO-0001-S1", UUID.randomUUID(), UUID.randomUUID(), WAREHOUSE, UUID.randomUUID(),
                mode, packages, new BigDecimal(cod), null, UUID.randomUUID(), NOW.minusSeconds(3600));
    }

    /** The shipment as a row, in the given status (read through the real transitions). */
    static ShipmentJpaEntity row(Shipment shipment) {
        shipment.pullRecorded();
        return ShipmentJpaEntity.of(shipment);
    }

    static ShipmentJpaEntity rowIn(ShipmentStatus status) {
        Shipment s = readyShipment();
        UUID by = UUID.randomUUID();
        UUID carrier = UUID.randomUUID();
        UUID load = UUID.randomUUID();
        switch (status) {
            case READY_TO_DISPATCH -> { }
            case HANDED_OVER -> s.handOver(load, carrier, null, false, by, NOW);
            case DELIVERED -> {
                s.handOver(load, carrier, null, false, by, NOW);
                s.recordDelivered(by, NOW);
            }
            case DELIVERY_FAILED -> {
                s.handOver(load, carrier, null, false, by, NOW);
                s.recordFailed(com.stockflow.fulfillment.internal.domain.DeliveryFailureReason.REFUSED, null, by, NOW);
            }
            case RETURNED -> {
                s.handOver(load, carrier, null, false, by, NOW);
                s.recordFailed(com.stockflow.fulfillment.internal.domain.DeliveryFailureReason.REFUSED, null, by, NOW);
                s.recordReturned(by, NOW);
            }
            case CANCELLED -> s.cancel("order cancelled", by, NOW);
            default -> throw new IllegalArgumentException(status.name());
        }
        return row(s);
    }

    /** An order as the order module reports it, with the given shipments recorded on it (null for no lifecycle). */
    static OrderSummary order(UUID id, List<OrderSummary.Shipment> shipments) {
        OrderSummary.Lifecycle lifecycle = shipments == null ? null : new OrderSummary.Lifecycle(null, null, null, NOW,
                null, null, null, null, null, BigDecimal.ZERO, new BigDecimal("1000000"), new BigDecimal("1000000"),
                BigDecimal.ZERO, List.of(), shipments);
        return new OrderSummary(id, "SO-0001", UUID.randomUUID(), OrderStatus.SHIPPED, Money.vnd(1_000_000), List.of(),
                NOW.minusSeconds(86_400), null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW,
                null, null, lifecycle);
    }

    /** An order in the given status with the given lines and, when {@code holdReason} is set, held for it. */
    static OrderSummary orderWith(UUID id, OrderStatus status, List<OrderSummary.LineSummary> lines,
                                  com.stockflow.order.api.HoldReason holdReason) {
        OrderSummary.Lifecycle lifecycle = new OrderSummary.Lifecycle(holdReason, null, null, null, null, null, null,
                null, null, BigDecimal.ZERO, new BigDecimal("1000000"), new BigDecimal("1000000"), BigDecimal.ZERO,
                List.of(), List.of());
        return new OrderSummary(id, "SO-0001", UUID.randomUUID(), status, Money.vnd(1_000_000), lines,
                NOW.minusSeconds(86_400), null, null, null, null, null, null, null, null, null, WAREHOUSE, NOW,
                null, null, lifecycle);
    }

    static OrderSummary.LineSummary stockLine(String sku, UUID... reservations) {
        return new OrderSummary.LineSummary(UUID.randomUUID(), sku, 5, Money.vnd(10_000), Money.vnd(50_000),
                List.of(reservations));
    }

    static ErrorCode codeOf(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }

    static BusinessException refusal(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown;
    }

    // ---- parcels and load plans ------------------------------------------------------------------------

    /** A sealed carton of the shipment, {@code l x w x h} cm and {@code kg} (null sizes: not measured). */
    static com.stockflow.fulfillment.internal.entity.PackageJpaEntity parcel(UUID shipmentId, int no, String l,
                                                                              String w, String h, String kg) {
        var parcel = new com.stockflow.fulfillment.internal.entity.PackageJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                no, "PKG-" + no);
        parcel.apply(shipmentId, com.stockflow.fulfillment.internal.domain.PackageType.CARTON,
                com.stockflow.fulfillment.internal.domain.PackageStatus.SEALED, kg == null ? null : new BigDecimal(kg),
                l == null ? null : new BigDecimal(l), w == null ? null : new BigDecimal(w),
                h == null ? null : new BigDecimal(h), null, true, false, false, false, NOW, null, null, null);
        return parcel;
    }

    /** A 2 x 1 x 1 m truck carrying 1 t, the vehicle of the domain tests. */
    static final UUID TRUCK_TYPE = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

    /**
     * A CONFIRMED full-truckload plan of one shipment's three 1 m cubes on one truck that holds two: two parcels are
     * placed, the third is left behind (09 §4.2 step 3 edge).
     */
    record LeftBehindPlan(com.stockflow.fulfillment.internal.domain.LoadPlan plan, UUID shipmentId, UUID leftBehind,
                          java.util.Set<UUID> placed) { }

    /** A PLANNED plan of one shipment's 1 m cubes on trucks of 2 x 1 x 1 m, with the cartons the planner saw. */
    record PlannedPlan(com.stockflow.fulfillment.internal.domain.LoadPlan plan, UUID shipmentId, UUID dispatcher,
                       java.util.Map<UUID, com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage> cartons,
                       com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec truck) { }

    static PlannedPlan plannedPlan(int cubes, int trucks) {
        return plannedPlan(cubes, trucks, com.stockflow.fulfillment.internal.domain.LoadPlan.SOURCE_ORDER);
    }

    /** As {@link #plannedPlan(int, int)}; {@code sourceType} TRANSFER makes the key of its parcels a transfer order. */
    static PlannedPlan plannedPlan(int cubes, int trucks, String sourceType) {
        UUID shipmentId = UUID.randomUUID();
        java.util.Map<UUID, com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage> cartons =
                new java.util.LinkedHashMap<>();
        java.util.Map<UUID, UUID> shipmentOf = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cubes; i++) {
            UUID id = UUID.randomUUID();
            cartons.put(id, new com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage(id.toString(), null,
                    1000, 1000, 1000, new BigDecimal("100"), true, "CUBE", null, shipmentId.toString(), false));
            shipmentOf.put(id, shipmentId);
        }
        var truck = new com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec(TRUCK_TYPE, "TRUCK-TEST",
                com.stockflow.fulfillment.internal.domain.loadplanning.VehicleKind.TRUCK, 2000, 1000, 1000,
                new BigDecimal("1000"), trucks, 0);
        var plan = com.stockflow.fulfillment.internal.domain.LoadPlan.draft(UUID.randomUUID(), "LP-20261022-0001",
                UUID.randomUUID(), WAREHOUSE,
                com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode.SINGLE_TYPE, null, sourceType);
        UUID dispatcher = UUID.randomUUID();
        plan.setInputs(shipmentOf, List.of(TRUCK_TYPE), null, dispatcher, NOW);
        var result = new com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanner().plan(
                new com.stockflow.fulfillment.internal.domain.loadplanning.LoadPlanRequest(List.copyOf(cartons.values()),
                        List.of(truck), com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode.SINGLE_TYPE,
                        List.of(), com.stockflow.fulfillment.internal.domain.loadplanning.KitDistribution.AUTO, false,
                        null, 0L, java.time.Duration.ofSeconds(2)));
        plan.applyCalculation(result, java.util.Map.of(TRUCK_TYPE, truck), UUID::randomUUID, dispatcher, NOW, cartons);
        return new PlannedPlan(plan, shipmentId, dispatcher, cartons, truck);
    }

    static LeftBehindPlan confirmedPlanLeavingOneBehind() {
        PlannedPlan planned = plannedPlan(3, 1);
        var plan = planned.plan();
        plan.acknowledgeUnplaced(planned.dispatcher(), NOW, null, planned.cartons());
        UUID left = plan.unplacedPackageIds().iterator().next();
        plan.confirm(planned.dispatcher(), NOW, planned.cartons());
        return new LeftBehindPlan(plan, planned.shipmentId(), left, plan.placedPackageIds());
    }
}
