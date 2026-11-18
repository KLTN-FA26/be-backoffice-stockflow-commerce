package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.loadplanning.Layout;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.service.LoadPlans.ParcelView;
import com.stockflow.fulfillment.internal.service.LoadPlans.PlanDetail;
import com.stockflow.fulfillment.internal.service.LoadPlans.VehicleSummary;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The loading sheet PDF of one vehicle of a load plan (kltn-docs 09 §4.2 step 5; {@link LoadingSheetDocument}), put
 * together from what the plan already answers - {@link LoadPlans#get} (the plan, its vehicles, the dispatch load each
 * confirmed vehicle became, its parcels and their orders) and {@link LoadPlans#layout} (the stored placements in loading
 * order) - so it shows exactly what the screens show, under the same checks: the caller's warehouses only (403
 * {@code OUT_OF_DATA_SCOPE}), 404 {@code LOAD_PLAN_NOT_FOUND} and {@code LOAD_PLAN_VEHICLE_NOT_FOUND}. The booking
 * (carrier, waybill, plate, driver) is the vehicle's dispatch load's, read here; a load called off shows none.
 *
 * <p>Printable in every status of the plan, as the layout is readable in every status; a plan not yet confirmed or
 * cancelled says so on the sheet.</p>
 */
@Service
@Transactional(readOnly = true)
public class LoadingSheetService {

    /** The PDF and the name it is offered under. */
    public record Pdf(String fileName, byte[] content) {
    }

    private final LoadPlans plans;
    private final DispatchLoadJpaRepository loads;
    private final CarrierJpaRepository carriers;
    private final WarehouseService warehouses;
    private final LoadingSheetDocument document;
    private final Clock clock;

    LoadingSheetService(LoadPlans plans, DispatchLoadJpaRepository loads, CarrierJpaRepository carriers,
                        WarehouseService warehouses, LoadingSheetDocument document, Clock clock) {
        this.plans = plans;
        this.loads = loads;
        this.carriers = carriers;
        this.warehouses = warehouses;
        this.document = document;
        this.clock = clock;
    }

    /**
     * @param seq the vehicle's number in the plan
     * @throws com.stockflow.common.error.BusinessException {@code LOAD_PLAN_NOT_FOUND}, {@code OUT_OF_DATA_SCOPE},
     *         {@code LOAD_PLAN_VEHICLE_NOT_FOUND}
     */
    public Pdf loadingSheet(UUID loadPlanId, int seq) {
        PlanDetail plan = plans.get(loadPlanId);
        Layout layout = plans.layout(loadPlanId, seq);
        Optional<VehicleSummary> vehicle = plan.vehicles().stream().filter(v -> v.seq() == seq).findFirst();
        String vehicleType = plan.vehicleTypes().stream()
                .filter(type -> vehicle.isPresent() && type.vehicleTypeId().equals(vehicle.get().vehicleTypeId()))
                .map(LoadPlans.VehicleTypeChoice::name).findFirst().orElse(null);
        Optional<DispatchLoadJpaEntity> load = vehicle.map(VehicleSummary::load)
                .flatMap(ref -> ref == null ? Optional.empty() : loads.findById(ref.loadId()))
                .filter(l -> l.getStatus() != LoadStatus.CANCELLED);
        String carrier = load.map(DispatchLoadJpaEntity::getCarrierId)
                .flatMap(id -> id == null ? Optional.empty() : carriers.findById(id))
                .map(CarrierJpaEntity::getName).orElse(null);

        Map<UUID, String> orderOfShipment = new HashMap<>();
        plan.shipments().forEach(s -> orderOfShipment.put(s.shipmentId(), s.orderNumber()));
        Map<String, String> orderByRef = new HashMap<>();
        for (ParcelView parcel : plan.packages()) {
            String order = orderOfShipment.get(parcel.shipmentId());
            if (order != null) {
                orderByRef.put(parcel.packageId().toString(), order);
            }
        }
        String warehouse = warehouses.findWarehouse(plan.warehouseId()).map(WarehouseView::name).orElse(null);

        LoadingSheetDocument.Facts facts = new LoadingSheetDocument.Facts(plan.code(), plan.status(), vehicleType,
                warehouse, load.map(DispatchLoadJpaEntity::getCode).orElse(null), carrier,
                load.map(DispatchLoadJpaEntity::getWaybillNo).orElse(null),
                load.map(DispatchLoadJpaEntity::getVehiclePlate).orElse(null),
                load.map(DispatchLoadJpaEntity::getDriverName).orElse(null),
                load.map(DispatchLoadJpaEntity::getDriverPhone).orElse(null), layout, orderByRef, clock.instant());
        return new Pdf("loading-sheet-%s-xe%d.pdf".formatted(plan.code(), seq), document.render(facts));
    }
}
