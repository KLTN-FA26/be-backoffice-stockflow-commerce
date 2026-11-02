package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.KitPart;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.parcel;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sealed parcels as load planning sees them (kltn-docs 09 BR-01, BR-09): sizes rounded UP to whole millimetres, a parcel
 * without sizes or weight is not plannable, and what a parcel holds names its part.
 */
class LoadPlanParcelsTest {

    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final PackageLineJpaRepository lines = mock(PackageLineJpaRepository.class);
    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final TransferFulfillment transfers = mock(TransferFulfillment.class);
    private final LoadPlanParcels parcels = new LoadPlanParcels(packages, lines, shipments, orders, transfers);

    @Test
    @DisplayName("centimetres with two decimals become whole millimetres, rounded up and never under-stated")
    void sizesRoundUp() {
        assertThat(LoadPlanParcels.mm(new BigDecimal("10.00"))).isEqualTo(100);
        assertThat(LoadPlanParcels.mm(new BigDecimal("12.31"))).isEqualTo(124);
        assertThat(LoadPlanParcels.mm(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(LoadPlanParcels.mm(new BigDecimal("59.90"))).isEqualTo(599);
    }

    @Test
    @DisplayName("a parcel as the planner sees it: its three sides, weight, rotation flag and shipment as the group")
    void cartonOfAParcel() {
        UUID shipment = UUID.randomUUID();
        PackageJpaEntity p = parcel(shipment, 1, "60.5", "40", "30.01", "12.5");

        CargoPackage carton = LoadPlanParcels.carton(p, "SKU-A");

        assertThat(carton.ref()).isEqualTo(p.getId().toString());
        assertThat(carton.lengthMm()).isEqualTo(605);
        assertThat(carton.widthMm()).isEqualTo(400);
        assertThat(carton.heightMm()).isEqualTo(301);
        assertThat(carton.weightKg()).isEqualByComparingTo("12.5");
        assertThat(carton.rotatable()).isTrue();
        assertThat(carton.partCode()).isEqualTo("SKU-A");
        assertThat(carton.groupKey()).isEqualTo(shipment.toString());
        assertThat(carton.kitGroup()).isNull();
    }

    @Test
    @DisplayName("a parcel of a transfer is grouped by its transfer order")
    void transferParcelGroupsByTransfer() {
        UUID transfer = UUID.randomUUID();
        PackageJpaEntity p = new PackageJpaEntity(UUID.randomUUID(), UUID.randomUUID(), 1, "PKG-1", null, transfer);
        p.apply(null, com.stockflow.fulfillment.internal.domain.PackageType.CARTON, PackageStatus.SEALED,
                new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("30"), new BigDecimal("30"), null, true, false,
                false, false, NOW, null, null, null);

        assertThat(LoadPlanParcels.carton(p, "X").groupKey()).isEqualTo(transfer.toString());
    }

    @Test
    @DisplayName("a kit part carries its kit's group, so the parts of a kit stay on one vehicle (LP-3)")
    void kitPartCarriesItsGroup() {
        UUID group = UUID.randomUUID();
        PackageJpaEntity p = new PackageJpaEntity(UUID.randomUUID(), UUID.randomUUID(), 1, "PKG-1",
                new KitPart(group, 1, 2, UUID.randomUUID()));
        p.apply(UUID.randomUUID(), com.stockflow.fulfillment.internal.domain.PackageType.CARTON, PackageStatus.SEALED,
                new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("30"), new BigDecimal("30"), null, true, false,
                false, false, NOW, null, null, null);

        assertThat(LoadPlanParcels.carton(p, "SKU-K 1/2").kitGroup()).isEqualTo(group.toString());
    }

    @Test
    @DisplayName("a parcel with a missing or zero size or weight has no carton: it cannot be planned (09 BR-09)")
    void unmeasuredParcelHasNoCarton() {
        UUID s = UUID.randomUUID();

        assertThat(LoadPlanParcels.carton(parcel(s, 1, null, "40", "30", "5"), "A")).isNull();
        assertThat(LoadPlanParcels.carton(parcel(s, 1, "40", "0", "30", "5"), "A")).isNull();
        assertThat(LoadPlanParcels.carton(parcel(s, 1, "40", "40", "30", null), "A")).isNull();
        assertThat(LoadPlanParcels.carton(parcel(s, 1, "40", "40", "30", "0"), "A")).isNull();
    }

    @Test
    @DisplayName("only a sealed parcel with sizes and weight is plannable")
    void plannableNeedsSealedAndMeasured() {
        UUID s = UUID.randomUUID();
        PackageJpaEntity good = parcel(s, 1, "40", "40", "30", "5");
        PackageJpaEntity open = parcel(s, 2, "40", "40", "30", "5");
        open.apply(s, com.stockflow.fulfillment.internal.domain.PackageType.CARTON, PackageStatus.OPEN,
                new BigDecimal("5"), new BigDecimal("40"), new BigDecimal("40"), new BigDecimal("30"), null, true, false,
                false, false, null, null, null, null);
        PackageJpaEntity unmeasured = parcel(s, 3, null, null, null, null);

        assertThat(new LoadPlanParcels.Parcel(good, null, null, LoadPlanParcels.carton(good, "A"), "A").plannable())
                .isTrue();
        assertThat(new LoadPlanParcels.Parcel(open, null, null, LoadPlanParcels.carton(open, "A"), "A").plannable())
                .isFalse();
        assertThat(new LoadPlanParcels.Parcel(unmeasured, null, null, null, "A").plannable()).isFalse();
    }

    @Test
    @DisplayName("LOAD_PLAN_PACKAGE_UNMEASURED names each parcel that cannot be planned; a clean set passes")
    void requireMeasuredNamesEachProblem() {
        UUID s = UUID.randomUUID();
        PackageJpaEntity good = parcel(s, 1, "40", "40", "30", "5");
        PackageJpaEntity bad1 = parcel(s, 2, null, "40", "30", "5");
        PackageJpaEntity bad2 = parcel(s, 3, "40", "40", "30", null);
        LoadPlanParcels.requireMeasured(List.of(new LoadPlanParcels.Parcel(good, null, null,
                LoadPlanParcels.carton(good, "A"), "A")));

        var thrown = refusal(() -> LoadPlanParcels.requireMeasured(List.of(
                new LoadPlanParcels.Parcel(good, null, null, LoadPlanParcels.carton(good, "A"), "A"),
                new LoadPlanParcels.Parcel(bad1, null, null, null, "A"),
                new LoadPlanParcels.Parcel(bad2, null, null, null, "A"))));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.LOAD_PLAN_PACKAGE_UNMEASURED);
        assertThat(thrown.fieldErrors()).extracting(f -> f.field())
                .containsExactly(bad1.getId().toString(), bad2.getId().toString());
        assertThat(thrown.getMessage()).contains("2 package(s)");
    }

    @Test
    @DisplayName("parcels are returned by id in the order asked, unknown ids left out")
    void ofKeepsTheOrderAskedAndDropsUnknownIds() {
        UUID shipmentId = UUID.randomUUID();
        PackageJpaEntity first = parcel(shipmentId, 1, "40", "40", "30", "5");
        PackageJpaEntity second = parcel(shipmentId, 2, "40", "40", "30", "5");
        UUID unknown = UUID.randomUUID();
        List<UUID> asked = List.of(second.getId(), unknown, first.getId());
        when(packages.findAllById(asked)).thenReturn(List.of(first, second));

        Map<UUID, LoadPlanParcels.Parcel> found = parcels.of(asked);

        assertThat(found.keySet()).containsExactly(second.getId(), first.getId());
        assertThat(parcels.of(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a parcel's part name is its SKU, several joined in order for a mixed parcel, or its label when empty")
    void partNames() {
        UUID shipmentId = UUID.randomUUID();
        OrderSummary.LineSummary a = FulfillmentFixtures.stockLine("SKU-B");
        OrderSummary.LineSummary b = FulfillmentFixtures.stockLine("SKU-A");
        OrderSummary order = FulfillmentFixtures.orderWith(UUID.randomUUID(), OrderStatus.PACKED, List.of(a, b), null);
        ShipmentJpaEntity shipment = ShipmentJpaEntity.of(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                shipmentId, "SO-1-S1", order.orderId(), null, FulfillmentFixtures.WAREHOUSE, UUID.randomUUID(),
                com.stockflow.fulfillment.internal.domain.DeliveryMode.FULL_TRUCKLOAD, 3, BigDecimal.ZERO, null,
                UUID.randomUUID(), NOW));
        PackageJpaEntity mixed = parcel(shipmentId, 1, "40", "40", "30", "5");
        PackageJpaEntity single = parcel(shipmentId, 2, "40", "40", "30", "5");
        PackageJpaEntity empty = parcel(shipmentId, 3, "40", "40", "30", "5");
        when(shipments.findAllById(any())).thenReturn(List.of(shipment));
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));
        when(lines.findByPackageIdIn(any())).thenReturn(List.of(
                new PackageLineJpaEntity(UUID.randomUUID(), mixed.getId(), a.lineId(), 1),
                new PackageLineJpaEntity(UUID.randomUUID(), mixed.getId(), b.lineId(), 1),
                new PackageLineJpaEntity(UUID.randomUUID(), single.getId(), a.lineId(), 2)));

        Map<UUID, LoadPlanParcels.Parcel> described = parcels.describe(List.of(mixed, single, empty));

        assertThat(described.get(mixed.getId()).partName()).isEqualTo("SKU-A + SKU-B");
        assertThat(described.get(single.getId()).partName()).isEqualTo("SKU-B");
        assertThat(described.get(empty.getId()).partName()).isEqualTo("PKG-3");
        assertThat(described.get(single.getId()).order()).isSameAs(order);
        assertThat(described.get(single.getId()).sourceId()).isEqualTo(shipmentId);
        assertThat(described.get(single.getId()).plannable()).isTrue();
    }

    @Test
    @DisplayName("a kit part is named SKU n/N so the parts of a kit keep their own row")
    void kitPartName() {
        UUID shipmentId = UUID.randomUUID();
        OrderSummary.LineSummary line = FulfillmentFixtures.stockLine("SKU-KIT");
        PackageJpaEntity part = new PackageJpaEntity(UUID.randomUUID(), UUID.randomUUID(), 1, "PKG-1",
                new KitPart(UUID.randomUUID(), 2, 3, line.lineId()));
        part.apply(shipmentId, com.stockflow.fulfillment.internal.domain.PackageType.CARTON, PackageStatus.SEALED,
                new BigDecimal("5"), new BigDecimal("30"), new BigDecimal("30"), new BigDecimal("30"), null, true, false,
                false, false, NOW, null, null, null);
        OrderSummary order = FulfillmentFixtures.orderWith(UUID.randomUUID(), OrderStatus.PACKED, List.of(line), null);
        ShipmentJpaEntity shipment = ShipmentJpaEntity.of(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                shipmentId, "SO-1-S1", order.orderId(), null, FulfillmentFixtures.WAREHOUSE, UUID.randomUUID(),
                com.stockflow.fulfillment.internal.domain.DeliveryMode.FULL_TRUCKLOAD, 3, BigDecimal.ZERO, null,
                UUID.randomUUID(), NOW));
        when(shipments.findAllById(any())).thenReturn(List.of(shipment));
        when(orders.findById(order.orderId())).thenReturn(Optional.of(order));

        assertThat(parcels.describe(List.of(part)).get(part.getId()).partName()).isEqualTo("SKU-KIT 2/3");
    }

    @Test
    @DisplayName("each order is read once however many shipments it has")
    void ordersAreReadOnce() {
        UUID orderId = UUID.randomUUID();
        ShipmentJpaEntity one = ShipmentJpaEntity.of(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                UUID.randomUUID(), "S1", orderId, null, FulfillmentFixtures.WAREHOUSE, null,
                com.stockflow.fulfillment.internal.domain.DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null,
                UUID.randomUUID(), NOW));
        ShipmentJpaEntity two = ShipmentJpaEntity.of(com.stockflow.fulfillment.internal.domain.Shipment.ready(
                UUID.randomUUID(), "S2", orderId, null, FulfillmentFixtures.WAREHOUSE, null,
                com.stockflow.fulfillment.internal.domain.DeliveryMode.PARCEL, 1, BigDecimal.ZERO, null,
                UUID.randomUUID(), NOW));
        when(orders.findById(orderId)).thenReturn(Optional.of(FulfillmentFixtures.order(orderId, null)));

        Map<UUID, OrderSummary> read = parcels.ordersOf(List.of(one, two));

        assertThat(read).containsOnlyKeys(orderId);
        verify(orders, times(1)).findById(orderId);
    }
}
