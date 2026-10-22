package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.domain.EffectiveStatus;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository.LocationRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link WarehouseService}: the storage-location lookup other modules
 * call. Map administration is {@link WarehouseLayoutServiceImpl} and its siblings.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can
 * bypass the port by autowiring the concrete class. Read-only; called from another module's service
 * it joins that transaction, but takes no lock and freezes nothing - under {@code READ COMMITTED} the
 * location can change between this read and the caller's write. The caller's foreign key is the
 * last guard that the location still exists; nothing guards that it is still usable, and nothing
 * needs to: a location going out of use a moment after stock was put there is an ordinary race.</p>
 */
@Service
@Transactional(readOnly = true)
class WarehouseServiceImpl implements WarehouseService {

    private final LocationLookupRepository locations;
    private final com.stockflow.warehouse.internal.repository.SubcontractorSiteRepository sites;

    WarehouseServiceImpl(LocationLookupRepository locations,
                         com.stockflow.warehouse.internal.repository.SubcontractorSiteRepository sites) {
        this.locations = locations;
        this.sites = sites;
    }

    /** Upper-cased first: every part of a code is {@code [A-Z0-9]} (BR-10), and scanners and people disagree on case. */
    @Override
    public Optional<StorageLocationView> findLocation(String locationCode) {
        if (locationCode == null || locationCode.isBlank()) {
            return Optional.empty();
        }
        return locations.findByLocationCode(locationCode.strip().toUpperCase(Locale.ROOT))
                .map(WarehouseServiceImpl::toView);
    }

    @Override
    public Optional<StorageLocationView> findLocation(UUID locationId) {
        if (locationId == null) {
            return Optional.empty();
        }
        return locations.findById(locationId).map(WarehouseServiceImpl::toView);
    }

    @Override
    public java.util.List<StorageLocationView> areasOf(UUID warehouseId, StorageLocationView.AreaType type) {
        if (warehouseId == null || type == null) {
            return java.util.List.of();
        }
        return locations.findAreas(warehouseId, com.stockflow.warehouse.internal.domain.AreaType.valueOf(type.name()))
                .stream().map(WarehouseServiceImpl::toView).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public StorageLocationView subcontractorLocation(UUID warehouseId, UUID supplierId, String supplierCode) {
        java.util.Objects.requireNonNull(warehouseId, "warehouseId");
        java.util.Objects.requireNonNull(supplierId, "supplierId");
        String code = sites.locationOf(warehouseId, supplierId, supplierCode);
        return locations.findByLocationCode(code).map(WarehouseServiceImpl::toView).orElseThrow();
    }

    @Override
    public java.util.Map<String, UUID> pickableLocations(java.util.Collection<String> locationCodes) {
        java.util.Map<String, UUID> pickable = new java.util.HashMap<>();
        if (locationCodes == null || locationCodes.isEmpty()) {
            return pickable;
        }
        locations.findPickable(java.util.Set.copyOf(locationCodes)).stream()
                .map(WarehouseServiceImpl::toView)
                .filter(StorageLocationView::usable)
                .forEach(view -> pickable.put(view.locationCode(), view.warehouseId()));
        return pickable;
    }

    @Override
    public Optional<WarehouseView> findWarehouse(UUID warehouseId) {
        if (warehouseId == null) {
            return Optional.empty();
        }
        return locations.findWarehouseHeader(warehouseId).map(w -> new WarehouseView(w.id(), w.prefix(), w.name(),
                w.address(), w.status() == WarehouseStatus.ACTIVE));
    }

    private static StorageLocationView toView(LocationRow row) {
        LocationStatus effective = row.kind() == StorageLocationKind.BIN
                ? EffectiveStatus.ofBin(row.warehouseStatus(), row.shelfStatus(), row.status())
                : EffectiveStatus.ofArea(row.warehouseStatus(), row.status());
        return new StorageLocationView(row.id(), StorageLocationView.Kind.valueOf(row.kind().name()),
                row.warehouseId(), row.locationCode(),
                StorageLocationView.StorageClass.valueOf(row.storageClass().name()),
                StorageLocationView.Status.valueOf(row.status().name()),
                StorageLocationView.Status.valueOf(effective.name()), EffectiveStatus.isUsable(effective),
                row.areaType() == null ? null : StorageLocationView.AreaType.valueOf(row.areaType().name()));
    }
}
