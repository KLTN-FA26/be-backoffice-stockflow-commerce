package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sealed parcels as load planning sees them (SCRUM-287 PR D): the {@link CargoPackage} the planner and the validator
 * work on, and what a layout or a table shows of it. Read in one go for a set of parcels.
 *
 * <ul>
 *   <li>Sizes: the parcel is measured in cm with two decimals; the planner works in whole mm, so each side is
 *       {@code ceil(cm x 10)} - rounded up, never under-stated (the placement trigger of V20261022001300 uses the
 *       same rule). Length lies along the vehicle (z), width across (x), height up (y) when not turned.</li>
 *   <li>{@code ref} is the package id (design v2 §3: it replaces the original's {@code eoi}).</li>
 *   <li>The part name is what the parcel holds: its SKU (several, joined, for a mixed parcel); a kit part is
 *       {@code SKU n/N}, so the parts of a kit keep their own colour and row (LP2-5/LP2-6).</li>
 *   <li>{@code kitGroup} is the kit's group id: the planner keeps each physical set on one vehicle (LP-3).</li>
 *   <li>{@code groupKey} is the shipment (the transfer order of a transfer's parcel); a shipment may span vehicles (LP-3, LP2-7), so the planner does not keep it
 *       together.</li>
 * </ul>
 */
@Component
class LoadPlanParcels {

    /** One parcel with everything planning shows of it. */
    /**
     * @param carton its sizes as the planner sees them, or null when it has none; {@link #plannable} says whether a
     *               plan may take it (sealed, with sizes and weight)
     */
    record Parcel(PackageJpaEntity row, ShipmentJpaEntity shipment, OrderSummary order, CargoPackage carton,
                  String partName, com.stockflow.inventory.api.TransferFulfillment.TransferView transfer) {

        Parcel(PackageJpaEntity row, ShipmentJpaEntity shipment, OrderSummary order, CargoPackage carton,
               String partName) {
            this(row, shipment, order, carton, partName, null);
        }

        /** What it leaves with: its shipment, or its transfer order (SCRUM-287 PR T). */
        UUID sourceId() {
            return row.getShipmentId() != null ? row.getShipmentId() : row.getTransferOrderId();
        }

        UUID packageId() {
            return row.getId();
        }

        boolean plannable() {
            return carton != null && row.getStatus() == PackageStatus.SEALED;
        }
    }

    private final PackageJpaRepository packages;
    private final PackageLineJpaRepository lines;
    private final ShipmentJpaRepository shipments;
    private final OrderService orders;
    private final com.stockflow.inventory.api.TransferFulfillment transfers;

    LoadPlanParcels(PackageJpaRepository packages, PackageLineJpaRepository lines, ShipmentJpaRepository shipments,
                    OrderService orders, com.stockflow.inventory.api.TransferFulfillment transfers) {
        this.packages = packages;
        this.lines = lines;
        this.shipments = shipments;
        this.orders = orders;
        this.transfers = transfers;
    }

    /** These parcels by package id, in the order given; unknown ids are left out. */
    Map<UUID, Parcel> of(Collection<UUID> packageIds) {
        if (packageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PackageJpaEntity> rows = packages.findAllById(packageIds).stream()
                .collect(Collectors.toMap(PackageJpaEntity::getId, Function.identity()));
        List<PackageJpaEntity> ordered = new ArrayList<>();
        packageIds.forEach(id -> Optional.ofNullable(rows.get(id)).ifPresent(ordered::add));
        return describe(ordered);
    }

    /** These parcel rows, described. */
    Map<UUID, Parcel> describe(List<PackageJpaEntity> rows) {
        Map<UUID, ShipmentJpaEntity> shipmentById = shipments.findAllById(rows.stream()
                        .map(PackageJpaEntity::getShipmentId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(ShipmentJpaEntity::getId, Function.identity()));
        Map<UUID, OrderSummary> orderById = ordersOf(shipmentById.values());
        Map<UUID, List<PackageLineJpaEntity>> linesByPackage = lines.findByPackageIdIn(rows.stream()
                        .map(PackageJpaEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(PackageLineJpaEntity::getPackageId));
        Map<UUID, com.stockflow.inventory.api.TransferFulfillment.TransferView> transferById = new HashMap<>();
        List<UUID> transferIds = rows.stream().map(PackageJpaEntity::getTransferOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!transferIds.isEmpty()) {
            transfers.findAll(transferIds).forEach(t -> transferById.put(t.transferId(), t));
        }
        Map<UUID, Parcel> out = new LinkedHashMap<>();
        for (PackageJpaEntity row : rows) {
            ShipmentJpaEntity shipment = row.getShipmentId() == null ? null : shipmentById.get(row.getShipmentId());
            OrderSummary order = shipment == null ? null : orderById.get(shipment.getOrderId());
            var transfer = row.getTransferOrderId() == null ? null : transferById.get(row.getTransferOrderId());
            Map<UUID, String> skuByLine = new HashMap<>();
            if (order != null) {
                order.lines().forEach(l -> skuByLine.put(l.lineId(), l.sku()));
            }
            if (transfer != null) {
                transfer.lines().forEach(l -> skuByLine.put(l.lineId(), l.sku()));
            }
            String partName = partName(row, linesByPackage.getOrDefault(row.getId(), List.of()), skuByLine);
            out.put(row.getId(), new Parcel(row, shipment, order, carton(row, partName), partName, transfer));
        }
        return out;
    }

    /** The orders of these shipments, read once each. */
    Map<UUID, OrderSummary> ordersOf(Collection<ShipmentJpaEntity> rows) {
        Map<UUID, OrderSummary> out = new HashMap<>();
        rows.stream().map(ShipmentJpaEntity::getOrderId).distinct()
                .forEach(id -> orders.findById(id).ifPresent(o -> out.put(id, o)));
        return out;
    }

    /**
     * The parcel as the planner sees it, or null when it has no sizes or weight (09 BR-09; edge "kiện thiếu kích thước
     * → chặn tính phương án").
     */
    static CargoPackage carton(PackageJpaEntity row, String partName) {
        if (!(positive(row.getLengthCm()) && positive(row.getWidthCm()) && positive(row.getHeightCm())
                && positive(row.getWeightKg()))) {
            return null;
        }
        String group = row.getKit() == null ? null : row.getKit().groupId().toString();
        return new CargoPackage(row.getId().toString(), null, mm(row.getLengthCm()), mm(row.getWidthCm()),
                mm(row.getHeightCm()), row.getWeightKg(), row.isRotatable(), partName, group,
                row.getShipmentId() != null ? row.getShipmentId().toString()
                        : row.getTransferOrderId() == null ? null : row.getTransferOrderId().toString(), false);
    }

    /** cm with two decimals to whole mm, rounded up. */
    static int mm(BigDecimal cm) {
        return cm.multiply(BigDecimal.TEN).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** {@code LOAD_PLAN_PACKAGE_UNMEASURED} naming each parcel that cannot be planned, or nothing. */
    static void requireMeasured(Collection<Parcel> parcels) {
        List<FieldError> problems = new ArrayList<>();
        for (Parcel p : parcels) {
            if (!p.plannable()) {
                problems.add(new FieldError(p.packageId().toString(), "package " + p.row().getLabelCode()
                        + " is not sealed with its sizes and weight", "LOAD_PLAN_PACKAGE_UNMEASURED"));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.LOAD_PLAN_PACKAGE_UNMEASURED,
                    "%d package(s) cannot be planned: measure them again at packing".formatted(problems.size()),
                    problems);
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String partName(PackageJpaEntity row, List<PackageLineJpaEntity> lines,
                                   Map<UUID, String> skuByLine) {
        if (row.getKit() != null) {
            String sku = skuByLine.getOrDefault(row.getKit().orderLineId(), row.getLabelCode());
            return sku + " " + row.getKit().partNo() + "/" + row.getKit().partCount();
        }
        TreeSet<String> skus = new TreeSet<>();
        lines.forEach(l -> skus.add(skuByLine.getOrDefault(l.getLineId(), "?")));
        return skus.isEmpty() ? row.getLabelCode() : String.join(" + ", skus);
    }
}
