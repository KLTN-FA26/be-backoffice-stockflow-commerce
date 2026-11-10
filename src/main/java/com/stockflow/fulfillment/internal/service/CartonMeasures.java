package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadPlanPackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.InventoryControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A shipping carton's size and weight from its SKU's carton spec (kltn-docs 08 BR-07, BR-09), for the label and for
 * load planning.
 *
 * <p>08 edge case "the real carton differs from the declared spec": the warehouse manager corrects the spec on the
 * item, and a carton already checked takes the new one <b>when its label is printed again or when its vehicle is
 * planned</b>. The label does it through {@link CartonCheck#reprint}; planning through {@link #refreshForPlanning}, as
 * its shipment's cartons join a load plan. A carton a load plan or a vehicle already holds keeps the measures its
 * layout was computed from: {@code tg_package_in_load_plan} refuses to change them, and a layout that no longer matches
 * its cartons would put a vehicle together wrongly.</p>
 */
@Component
class CartonMeasures {

    private static final Logger log = LoggerFactory.getLogger(CartonMeasures.class);

    private final InventoryControlService itemControls;
    private final PickLineJpaRepository pickLines;
    private final PackageJpaRepository packages;
    private final LoadPlanPackageJpaRepository planMembers;
    private final DispatchLoadPackageJpaRepository loadParcels;
    private final int divisor;

    CartonMeasures(InventoryControlService itemControls, PickLineJpaRepository pickLines, PackageJpaRepository packages,
                   LoadPlanPackageJpaRepository planMembers, DispatchLoadPackageJpaRepository loadParcels,
                   @Value("${stockflow.fulfillment.packing.dim-divisor:6000}") int divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("stockflow.fulfillment.packing.dim-divisor must be positive");
        }
        this.itemControls = itemControls;
        this.pickLines = pickLines;
        this.packages = packages;
        this.planMembers = planMembers;
        this.loadParcels = loadParcels;
        this.divisor = divisor;
    }

    /** The volumetric divisor (08 BR-09, 6000 by default). */
    int divisor() {
        return divisor;
    }

    /**
     * The carton spec of each of these SKUs, as the label wants it (08 §4.3 step 5, 01 BR-03). A SKU with no carton
     * unit is missing; one whose unit declares no size or weight is there, not measured.
     */
    Map<String, CartonCheck.Spec> specs(Collection<String> skus) {
        Map<String, CartonCheck.Spec> specs = new HashMap<>();
        skus.stream().distinct().forEach(sku -> itemControls.cartonSpec(new Sku(sku))
                .ifPresent(spec -> specs.put(sku, toSpec(spec))));
        return specs;
    }

    /** Whether a load plan or a vehicle holds this carton: its measures then stay what its layout was computed from. */
    boolean planned(UUID packageId) {
        return !planMembers.findByPackageIdInAndActiveTrue(List.of(packageId)).isEmpty()
                || loadParcels.findByPackageIdAndActiveTrue(packageId).isPresent();
    }

    /**
     * The labelled cartons among these parcels take their SKU's carton spec as it is now, unless a load plan or a
     * vehicle holds them already (08 edge case: the spec corrected after the check applies when the vehicle is
     * planned). A SKU whose spec is gone or no longer measured keeps the measures the carton has. Called as the
     * parcels join a load plan, under the locks of their tasks and shipments, before the plan checks they are measured;
     * a repack parcel is left as it is. Changed rows are flushed, so a database rule refuses inside the call.
     */
    void refreshForPlanning(Collection<PackageJpaEntity> parcels) {
        List<PackageJpaEntity> cartons = parcels.stream()
                .filter(p -> p.getPickLineId() != null && p.getStatus() == PackageStatus.LABELED).toList();
        if (cartons.isEmpty()) {
            return;
        }
        Set<UUID> held = new HashSet<>();
        planMembers.findByPackageIdInAndActiveTrue(cartons.stream().map(PackageJpaEntity::getId).toList())
                .stream().map(LoadPlanPackageJpaEntity::getPackageId).forEach(held::add);
        loadParcels.findByShipmentIdInAndActiveTrue(cartons.stream().map(PackageJpaEntity::getShipmentId)
                        .filter(Objects::nonNull).distinct().toList())
                .stream().map(DispatchLoadPackageJpaEntity::getPackageId).forEach(held::add);
        List<PackageJpaEntity> free = cartons.stream().filter(p -> !held.contains(p.getId())).toList();
        if (free.isEmpty()) {
            return;
        }
        Map<UUID, String> skuByLine = pickLines.findAllById(free.stream().map(PackageJpaEntity::getPickLineId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(PickLineJpaEntity::getId, PickLineJpaEntity::getSku));
        Map<String, CartonCheck.Spec> specs = specs(skuByLine.values());
        List<PackageJpaEntity> changed = new ArrayList<>();
        for (PackageJpaEntity carton : free) {
            CartonCheck.Spec spec = specs.get(skuByLine.get(carton.getPickLineId()));
            if (spec == null || !spec.measured() || sameAs(carton, spec)) {
                continue;
            }
            carton.remeasure(spec.weightKg(), spec.lengthCm(), spec.widthCm(), spec.heightCm(),
                    spec.dimWeightKg(divisor), spec.rotatable());
            changed.add(carton);
        }
        if (!changed.isEmpty()) {
            packages.saveAllAndFlush(changed);
            log.info("{} labelled carton(s) re-measured from a corrected carton spec as they join a load plan: {}",
                    changed.size(), changed.stream().map(PackageJpaEntity::getLpn).toList());
        }
    }

    private static boolean sameAs(PackageJpaEntity carton, CartonCheck.Spec spec) {
        return same(carton.getLengthCm(), spec.lengthCm()) && same(carton.getWidthCm(), spec.widthCm())
                && same(carton.getHeightCm(), spec.heightCm()) && same(carton.getWeightKg(), spec.weightKg())
                && carton.isRotatable() == spec.rotatable();
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static CartonCheck.Spec toSpec(CartonSpec spec) {
        return new CartonCheck.Spec(spec.lengthCm(), spec.widthCm(), spec.heightCm(), spec.weightKg(),
                spec.rotatable());
    }
}
