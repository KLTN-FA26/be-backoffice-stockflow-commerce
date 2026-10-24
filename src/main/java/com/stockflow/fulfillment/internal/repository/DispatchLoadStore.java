package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadPackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadUnloadJpaEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads a {@link DispatchLoad} from its header, manifest rows and loaded parcels, and writes it back. A load holds a
 * few dozen rows at most, so every row is written on save; rows taken off are kept inactive, never deleted (who took
 * a parcel off and when stays on record).
 *
 * <p>Order of writes: rows going inactive before rows going active, then the header - the partial unique indexes
 * ({@code uk_dispatch_load_*_active}) and the triggers see the state each statement leaves behind.</p>
 */
@Component
public class DispatchLoadStore {

    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository members;
    private final DispatchLoadPackageJpaRepository parcels;
    private final DispatchLoadUnloadJpaRepository unloads;

    DispatchLoadStore(DispatchLoadJpaRepository loads, DispatchLoadShipmentJpaRepository members,
                      DispatchLoadPackageJpaRepository parcels, DispatchLoadUnloadJpaRepository unloads) {
        this.loads = loads;
        this.members = members;
        this.parcels = parcels;
        this.unloads = unloads;
    }

    public DispatchLoad load(DispatchLoadJpaEntity header) {
        List<DispatchLoad.Member> onManifest = members.findByLoadIdOrderByAddedAtAscIdAsc(header.getId()).stream()
                .map(r -> new DispatchLoad.Member(r.getKey(), r.isActive(), r.getAddedAt(), r.getAddedBy(),
                        r.getRemovedAt(), r.getRemovedBy(), r.getLabelCodAmount(), r.getCodAmount()))
                .toList();
        List<DispatchLoad.Loaded> onVehicle = parcels.findByLoadIdOrderByLoadedAtAscIdAsc(header.getId()).stream()
                .map(r -> new DispatchLoad.Loaded(r.getPackageId(), r.getKey(), r.getScanId(), r.getLoadedAt(),
                        r.getLoadedBy(), r.isActive(), r.getRemovedAt(), r.getRemovedBy()))
                .toList();
        List<DispatchLoad.Unload> toUnload = unloads.findByLoadIdOrderByRequestedAtAscIdAsc(header.getId()).stream()
                .map(r -> new DispatchLoad.Unload(r.getPackageId(), r.getShipmentId(), r.getReason(),
                        r.getRequestedAt(), r.getUnloadedAt(), r.getUnloadedBy()))
                .toList();
        return DispatchLoad.restore(new DispatchLoad.Snapshot(header.getId(), header.getCode(), header.getRequestId(),
                header.getWarehouseId(), header.getMode(), header.getCarrierId(), header.getLoadPlanVehicleId(),
                header.getVehiclePlate(), header.getDriverName(), header.getDriverPhone(), header.getStatus(),
                header.getLoadingStartedAt(), header.getLoadingStartedBy(), header.getDispatchedAt(),
                header.getDispatchedBy(), header.getCarrierRepresentative(), header.getSignatureRef(),
                header.getHandoverNote(), header.getCodTotal(), header.getCancelledAt(), header.getCancelledBy(),
                header.getCancelReason(), header.getVersion(), onManifest, onVehicle, header.getBookedAt(),
                header.getBookedBy(), header.getBookingCancelledAt(), header.getBookingCancelledBy(),
                header.getBookingCancelReason(), toUnload));
    }

    /** Inserts a new load (header first: the manifest rows reference it). */
    public DispatchLoadJpaEntity insert(DispatchLoad load) {
        return insert(load, false);
    }

    /**
     * Inserts a new load; {@code transfer}: a vehicle of a TRANSFER plan (SCRUM-287 PR T), whose manifest holds its
     * transfer order where a load's holds shipments ({@code DispatchLoad} calls both "shipments").
     */
    public DispatchLoadJpaEntity insert(DispatchLoad load, boolean transfer) {
        DispatchLoad.Snapshot s = load.snapshot();
        DispatchLoadJpaEntity header = new DispatchLoadJpaEntity(s);
        header.apply(s);
        header = loads.saveAndFlush(header);
        writeRows(s, transfer);
        return header;
    }

    public DispatchLoadJpaEntity save(DispatchLoad load, DispatchLoadJpaEntity header) {
        DispatchLoad.Snapshot s = load.snapshot();
        writeRows(s, null);
        header.apply(s);
        return loads.saveAndFlush(header);
    }

    /** @param transferLoad null: as the stored manifest says (a TRANSFER plan's vehicle got its transfer when opened) */
    private void writeRows(DispatchLoad.Snapshot s, Boolean transferLoad) {
        Map<UUID, DispatchLoadShipmentJpaEntity> manifest = members.findByLoadIdOrderByAddedAtAscIdAsc(s.id()).stream()
                .collect(Collectors.toMap(DispatchLoadShipmentJpaEntity::getKey, Function.identity()));
        boolean transfer = transferLoad != null ? transferLoad
                : manifest.values().stream().anyMatch(r -> r.getTransferOrderId() != null);
        Map<UUID, DispatchLoadPackageJpaEntity> vehicle = parcels.findByLoadIdOrderByLoadedAtAscIdAsc(s.id()).stream()
                .collect(Collectors.toMap(DispatchLoadPackageJpaEntity::getPackageId, Function.identity()));
        List<DispatchLoadPackageJpaEntity> parcelsOff = new ArrayList<>();
        List<DispatchLoadPackageJpaEntity> parcelsOn = new ArrayList<>();
        for (DispatchLoad.Loaded l : s.loaded()) {
            DispatchLoadPackageJpaEntity row = vehicle.get(l.packageId());
            if (row != null && row.isActive() == l.active() && row.getScanId().equals(l.scanId())) {
                continue;
            }
            if (row == null) {
                row = new DispatchLoadPackageJpaEntity(Identifiers.newId(), s.id(), l.packageId(), l.shipmentId(),
                        transfer);
            }
            row.apply(l.scanId(), l.loadedAt(), l.loadedBy(), l.active(), l.removedAt(), l.removedBy());
            (l.active() ? parcelsOn : parcelsOff).add(row);
        }
        List<DispatchLoadShipmentJpaEntity> shipmentsOff = new ArrayList<>();
        List<DispatchLoadShipmentJpaEntity> shipmentsOn = new ArrayList<>();
        for (DispatchLoad.Member m : s.members()) {
            DispatchLoadShipmentJpaEntity row = manifest.get(m.shipmentId());
            if (row == null) {
                row = new DispatchLoadShipmentJpaEntity(Identifiers.newId(), s.id(), m.shipmentId(), s.mode(), transfer);
            }
            boolean wasActive = row.isActive();
            row.apply(m.active(), m.addedAt(), m.addedBy(), m.removedAt(), m.removedBy(), m.labelCodAmount(),
                    m.codAmount());
            (m.active() && !wasActive ? shipmentsOn : shipmentsOff).add(row);
        }
        // Off before on: a parcel off the vehicle before its shipment leaves the manifest, a shipment on the manifest
        // before its parcels are loaded (fk_dispatch_load_package_shipment, tg_dispatch_load_package_check).
        parcels.saveAllAndFlush(parcelsOff);
        members.saveAllAndFlush(shipmentsOff);
        members.saveAllAndFlush(shipmentsOn);
        parcels.saveAllAndFlush(parcelsOn);
        writeUnloads(s);
    }

    /** SCRUM-392: parcels to unload, before the header (tg_dispatch_load_unload_pending reads them). */
    private void writeUnloads(DispatchLoad.Snapshot s) {
        if (s.unloads() == null || s.unloads().isEmpty()) {
            return;
        }
        Map<UUID, DispatchLoadUnloadJpaEntity> known = unloads.findByLoadIdOrderByRequestedAtAscIdAsc(s.id()).stream()
                .collect(Collectors.toMap(DispatchLoadUnloadJpaEntity::getPackageId, Function.identity()));
        List<DispatchLoadUnloadJpaEntity> changed = new ArrayList<>();
        for (DispatchLoad.Unload u : s.unloads()) {
            DispatchLoadUnloadJpaEntity row = known.get(u.packageId());
            if (row == null) {
                row = new DispatchLoadUnloadJpaEntity(Identifiers.newId(), s.id(), u.packageId(), u.shipmentId(),
                        u.reason(), u.requestedAt());
            } else if (java.util.Objects.equals(row.getUnloadedAt(), u.unloadedAt())) {
                continue;
            }
            row.apply(u.unloadedAt(), u.unloadedBy());
            changed.add(row);
        }
        unloads.saveAllAndFlush(changed);
    }
}
