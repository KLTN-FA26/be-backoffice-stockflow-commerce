package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.loadplanning.CargoPackage;
import com.stockflow.fulfillment.internal.domain.loadplanning.PlacementValidator;
import com.stockflow.fulfillment.internal.domain.loadplanning.VehicleSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A stored load-plan vehicle to the {@code Layout} contract of the 3D viewer (FE #71
 * {@code src/features/load-planning/schemas.ts}; design v2 §5), all in mm: x across, y up, z toward the door, origin the
 * inner-left floor corner by the front wall.
 *
 * <p>Load plans only: the estimate (PR EST) maps the solver's output to the same contract with its own mapper; a later
 * small PR may unify the two. Nothing here is shared on purpose, so neither PR waits for the other.</p>
 */
final class LoadPlanLayoutMapper {

    private LoadPlanLayoutMapper() {
    }

    static LoadPlans.Layout toLayout(LoadPlan.Vehicle vehicle, Map<UUID, LoadPlanParcels.Parcel> parcels) {
        VehicleSpec spec = vehicle.spec();
        List<LoadPlans.BlockMm> gussets = new ArrayList<>();
        for (PlacementValidator.Block g : PlacementValidator.gussets(spec)) {
            gussets.add(new LoadPlans.BlockMm(g.x(), g.y(), g.z(), g.w(), g.h(), g.d()));
        }
        List<LoadPlans.LayoutBox> boxes = new ArrayList<>();
        for (LoadPlan.Placed p : vehicle.placements()) {
            LoadPlanParcels.Parcel parcel = parcels.get(p.packageId());
            CargoPackage c = parcel.carton();
            String kitGroup = parcel.row().getKit() == null ? null : parcel.row().getKit().groupId().toString();
            boxes.add(new LoadPlans.LayoutBox(p.x(), p.y(), p.z(), p.w(), p.h(), p.d(), p.packageId().toString(), null,
                    parcel.row().getLabelCode(), parcel.partName(), kitGroup,
                    new LoadPlans.DimsMm(c.lengthMm(), c.widthMm(), c.heightMm()), p.orientation().code(),
                    c.rotatable(), c.weightKg()));
        }
        return new LoadPlans.Layout(vehicle.seq(), spec.code(),
                new LoadPlans.DimsMm(spec.innerLengthMm(), spec.innerWidthMm(), spec.innerHeightMm()),
                spec.maxPayloadKg(), gussets, boxes, vehicle.usedVolumeMm3(), vehicle.usedWeightKg());
    }
}
