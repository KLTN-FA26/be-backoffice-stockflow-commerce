package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.entity.LoadPlacementJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadPlanVehicleTypeJpaEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads a {@link LoadPlan} from its header and child rows, and writes it back (SCRUM-287 PR D).
 *
 * <p>Order of writes - the partial unique indexes and the placement trigger see the state each step leaves behind:
 * parcels taken in; vehicles retired (so a new vehicle may take the seq of one it replaces,
 * {@code uk_load_plan_vehicle_seq}); placements that went away deleted; vehicles new or changed; placements new or
 * moved (their vehicle is active by then, {@code tg_load_placement_check}); parcels released (their placement is gone
 * by then); the vehicle types; the header last.</p>
 */
@Component
public class LoadPlanStore {

    private final LoadPlanJpaRepository plans;
    private final LoadPlanVehicleTypeJpaRepository types;
    private final LoadPlanPackageJpaRepository packages;
    private final LoadPlanVehicleJpaRepository vehicles;
    private final LoadPlacementJpaRepository placements;

    LoadPlanStore(LoadPlanJpaRepository plans, LoadPlanVehicleTypeJpaRepository types,
                  LoadPlanPackageJpaRepository packages, LoadPlanVehicleJpaRepository vehicles,
                  LoadPlacementJpaRepository placements) {
        this.plans = plans;
        this.types = types;
        this.packages = packages;
        this.vehicles = vehicles;
        this.placements = placements;
    }

    public LoadPlan load(LoadPlanJpaEntity header) {
        UUID planId = header.getId();
        List<UUID> typeIds = types.findByLoadPlanIdOrderBySortOrderAsc(planId).stream()
                .map(LoadPlanVehicleTypeJpaEntity::getVehicleTypeId).toList();
        List<LoadPlan.Member> members = packages.findByLoadPlanIdOrderByAddedAtAscIdAsc(planId).stream()
                .map(LoadPlanPackageJpaEntity::toMember).toList();
        Map<UUID, List<LoadPlan.Placed>> byVehicle = new HashMap<>();
        for (LoadPlacementJpaEntity row : placements.findByLoadPlanId(planId)) {
            byVehicle.computeIfAbsent(row.getLoadPlanVehicleId(), k -> new ArrayList<>()).add(row.toPlaced());
        }
        List<LoadPlan.Vehicle> vs = new ArrayList<>();
        for (LoadPlanVehicleJpaEntity row : vehicles.findByLoadPlanIdOrderBySeqAscCreatedAtAsc(planId)) {
            List<LoadPlan.Placed> placed = new ArrayList<>(byVehicle.getOrDefault(row.getId(), List.of()));
            placed.sort(java.util.Comparator.comparingInt(LoadPlan.Placed::z).thenComparingInt(LoadPlan.Placed::y)
                    .thenComparingInt(LoadPlan.Placed::x));
            vs.add(new LoadPlan.Vehicle(row.getId(), row.getSeq(), row.isActive(), row.toSpec(), placed,
                    row.getUsedVolumeMm3(), row.getUsedWeightKg(), row.getVolumeFillPct(), row.getWeightFillPct(),
                    row.isNeedsRecalculation()));
        }
        return LoadPlan.restore(new LoadPlan.Snapshot(planId, header.getCode(), header.getRequestId(),
                header.getWarehouseId(), header.getSourceType(), header.getSelectionMode(), header.getStatus(),
                header.getNote(), header.getCalculatedAt(), header.getCalculatedBy(), header.getAcknowledgedAt(),
                header.getAcknowledgedBy(), header.getAcknowledgeNote(), header.getConfirmedAt(),
                header.getConfirmedBy(), header.getReopenedAt(), header.getReopenedBy(), header.getReopenReason(),
                header.getDispatchedAt(), header.getCancelledAt(), header.getCancelledBy(), header.getCancelReason(),
                header.getVersion(), typeIds, members, vs));
    }

    /** Inserts a new plan with whatever it already holds. */
    public LoadPlanJpaEntity insert(LoadPlan plan, Instant at) {
        LoadPlan.Snapshot s = plan.snapshot();
        LoadPlanJpaEntity header = new LoadPlanJpaEntity(s);
        header.apply(s, at);
        header = plans.saveAndFlush(header);
        writeRows(s);
        return header;
    }

    /** Writes the plan back; the header always, so its version moves with any change. */
    public LoadPlanJpaEntity save(LoadPlan plan, LoadPlanJpaEntity header, Instant at) {
        LoadPlan.Snapshot s = plan.snapshot();
        writeRows(s);
        header.apply(s, at);
        return plans.saveAndFlush(header);
    }

    private void writeRows(LoadPlan.Snapshot s) {
        UUID planId = s.id();
        Map<UUID, LoadPlanPackageJpaEntity> memberRows = packages.findByLoadPlanIdOrderByAddedAtAscIdAsc(planId)
                .stream().collect(Collectors.toMap(LoadPlanPackageJpaEntity::getPackageId, Function.identity()));
        Map<UUID, LoadPlanVehicleJpaEntity> vehicleRows = vehicles.findByLoadPlanIdOrderBySeqAscCreatedAtAsc(planId)
                .stream().collect(Collectors.toMap(LoadPlanVehicleJpaEntity::getId, Function.identity()));
        Map<UUID, LoadPlacementJpaEntity> placementRows = placements.findByLoadPlanId(planId).stream()
                .collect(Collectors.toMap(LoadPlacementJpaEntity::getPackageId, Function.identity()));

        // 1. parcels taken in (or still held, with a new unplaced reason)
        List<LoadPlanPackageJpaEntity> membersOn = new ArrayList<>();
        List<LoadPlanPackageJpaEntity> membersOff = new ArrayList<>();
        for (LoadPlan.Member m : s.members()) {
            LoadPlanPackageJpaEntity row = memberRows.get(m.packageId());
            if (row != null && row.toMember().equals(m)) {
                continue;
            }
            if (row == null) {
                row = new LoadPlanPackageJpaEntity(Identifiers.newId(), planId, m.packageId());
            }
            row.apply(m);
            (m.active() ? membersOn : membersOff).add(row);
        }
        packages.saveAllAndFlush(membersOn);

        // 2. vehicles retired
        List<LoadPlanVehicleJpaEntity> retired = new ArrayList<>();
        List<LoadPlanVehicleJpaEntity> current = new ArrayList<>();
        Map<UUID, LoadPlan.Placed> wanted = new HashMap<>();
        Map<UUID, UUID> wantedVehicle = new HashMap<>();
        for (LoadPlan.Vehicle v : s.vehicles()) {
            LoadPlanVehicleJpaEntity row = vehicleRows.get(v.id());
            if (row == null) {
                row = new LoadPlanVehicleJpaEntity(v.id(), planId, v.seq(), v.spec());
            }
            boolean same = row.isActive() == v.active() && row.getUsedVolumeMm3() == v.usedVolumeMm3()
                    && row.isNeedsRecalculation() == v.needsRecalculation()
                    && row.getUsedWeightKg() != null && row.getUsedWeightKg().compareTo(v.usedWeightKg()) == 0
                    && row.getVolumeFillPct() != null && row.getVolumeFillPct().compareTo(v.volumeFillPct()) == 0
                    && row.getWeightFillPct() != null && row.getWeightFillPct().compareTo(v.weightFillPct()) == 0
                    && vehicleRows.containsKey(v.id());
            if (!same) {
                row.apply(v.active(), v.usedVolumeMm3(), v.usedWeightKg(), v.volumeFillPct(), v.weightFillPct(),
                        v.needsRecalculation());
                (v.active() ? current : retired).add(row);
            }
            if (v.active()) {
                for (LoadPlan.Placed p : v.placements()) {
                    wanted.put(p.packageId(), p);
                    wantedVehicle.put(p.packageId(), v.id());
                }
            }
        }
        vehicles.saveAllAndFlush(retired);

        // 3. placements that went away
        List<LoadPlacementJpaEntity> gone = placementRows.values().stream()
                .filter(row -> !wanted.containsKey(row.getPackageId())).toList();
        placements.deleteAllInBatch(gone);
        placements.flush();

        // 4. vehicles new or changed
        vehicles.saveAllAndFlush(current);

        // 5. placements new or moved
        List<LoadPlacementJpaEntity> write = new ArrayList<>();
        Set<UUID> deleted = new HashSet<>();
        gone.forEach(row -> deleted.add(row.getPackageId()));
        wanted.forEach((packageId, p) -> {
            UUID vehicleId = wantedVehicle.get(packageId);
            LoadPlacementJpaEntity row = deleted.contains(packageId) ? null : placementRows.get(packageId);
            if (row != null && row.sameAs(vehicleId, p)) {
                return;
            }
            if (row == null) {
                row = new LoadPlacementJpaEntity(Identifiers.newId(), planId, packageId);
            }
            row.apply(vehicleId, p);
            write.add(row);
        });
        placements.saveAllAndFlush(write);

        // 6. parcels released
        packages.saveAllAndFlush(membersOff);

        // 7. vehicle types, as listed
        Map<UUID, LoadPlanVehicleTypeJpaEntity> typeRows = types.findByLoadPlanIdOrderBySortOrderAsc(planId).stream()
                .collect(Collectors.toMap(LoadPlanVehicleTypeJpaEntity::getVehicleTypeId, Function.identity()));
        List<LoadPlanVehicleTypeJpaEntity> keep = new ArrayList<>();
        for (int i = 0; i < s.vehicleTypeIds().size(); i++) {
            UUID typeId = s.vehicleTypeIds().get(i);
            LoadPlanVehicleTypeJpaEntity row = typeRows.remove(typeId);
            if (row == null) {
                row = new LoadPlanVehicleTypeJpaEntity(Identifiers.newId(), planId, typeId, i);
            } else if (row.getSortOrder() == i) {
                continue;
            }
            row.setSortOrder(i);
            keep.add(row);
        }
        types.deleteAllInBatch(typeRows.values());
        types.saveAllAndFlush(keep);
    }
}
