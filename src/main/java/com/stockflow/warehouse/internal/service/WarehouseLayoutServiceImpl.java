package com.stockflow.warehouse.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.warehouse.internal.domain.CodePart;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import com.stockflow.warehouse.internal.domain.Zone;
import com.stockflow.warehouse.internal.domain.ZoneRepository;
import com.stockflow.warehouse.internal.repository.LayoutReadRepository;
import com.stockflow.warehouse.internal.repository.WarehouseSearchRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The transaction boundary of map administration. Orchestration only: load, call the aggregate,
 * save. The rules live in {@link Warehouse} and {@link Zone}.
 *
 * <p>Uniqueness (prefix, zone name) is checked before writing for a precise message; the unique
 * constraints catch two requests racing past the check, and the adapters translate those into the
 * same error codes.</p>
 */
@Service
@Transactional
class WarehouseLayoutServiceImpl implements WarehouseLayoutService {

    private static final SortWhitelist SORT = SortWhitelist
            .of("prefix", "name", "createdAt")
            .withDefault("prefix", Sort.Direction.ASC);

    private final WarehouseRepository warehouses;
    private final WarehouseSearchRepository warehouseSearch;
    private final ZoneRepository zones;
    private final LayoutReadRepository layoutRead;

    WarehouseLayoutServiceImpl(WarehouseRepository warehouses, WarehouseSearchRepository warehouseSearch,
                               ZoneRepository zones, LayoutReadRepository layoutRead) {
        this.warehouses = warehouses;
        this.warehouseSearch = warehouseSearch;
        this.zones = zones;
        this.layoutRead = layoutRead;
    }

    @Override
    public WarehouseSummary register(RegisterWarehouseCommand command) {
        String prefix = CodePart.of(command.prefix(), CodePart.PREFIX_MAX_LENGTH).value();
        if (warehouses.existsByPrefix(prefix)) {
            throw new ConflictException(ErrorCode.WAREHOUSE_PREFIX_ALREADY_EXISTS,
                    "Prefix " + prefix + " is already used by another warehouse");
        }
        Warehouse warehouse = Warehouse.register(Identifiers.newId(), prefix, command.name(),
                command.address(), command.returnAddress(), command.mapUnit(), command.mapWidth(),
                command.mapHeight());
        return toSummary(warehouses.save(warehouse));
    }

    /**
     * Takes the warehouse lock before reading how far the layout reaches, so no shelf or area can be
     * placed beyond the new edge between the check and the commit.
     */
    @Override
    public WarehouseSummary update(UpdateWarehouseCommand command) {
        Warehouse warehouse = warehouses.findByIdForUpdate(command.warehouseId())
                .orElseThrow(() -> warehouseNotFound(command.warehouseId()));
        warehouse.updateDetails(command.name(), command.address(), command.returnAddress(),
                command.expectedVersion());
        warehouse.resizeMap(command.mapWidth(), command.mapHeight(),
                warehouses.findOccupiedExtent(warehouse.id()));
        return toSummary(warehouses.save(warehouse));
    }

    @Override
    public WarehouseSummary activate(UUID warehouseId) {
        Warehouse warehouse = require(warehouseId);
        warehouse.activate();
        return toSummary(warehouses.save(warehouse));
    }

    @Override
    public WarehouseSummary deactivate(UUID warehouseId) {
        Warehouse warehouse = require(warehouseId);
        warehouse.deactivate();
        return toSummary(warehouses.save(warehouse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WarehouseSummary> list(ListWarehousesQuery query) {
        var pageable = Pages.of(query.page(), query.size(), SORT.parse(query.sort()));
        return Pages.toResponse(warehouseSearch.search(query.search(), query.status(), pageable)
                .map(WarehouseLayoutServiceImpl::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseSummary get(UUID warehouseId) {
        return toSummary(require(warehouseId));
    }

    @Override
    public ZoneSummary createZone(CreateZoneCommand command) {
        if (!warehouses.existsById(command.warehouseId())) {
            throw warehouseNotFound(command.warehouseId());
        }
        Zone zone = Zone.create(Identifiers.newId(), command.warehouseId(), command.name(), command.color());
        requireNameFree(zone);
        return toSummary(zones.save(zone));
    }

    @Override
    public ZoneSummary updateZone(UpdateZoneCommand command) {
        Zone zone = zones.findById(command.zoneId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOT_FOUND,
                        "Zone " + command.zoneId() + " not found"));
        zone.update(command.name(), command.color(), command.expectedVersion());
        requireNameFree(zone);
        return toSummary(zones.save(zone));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ZoneSummary> listZones(UUID warehouseId) {
        if (!warehouses.existsById(warehouseId)) {
            throw warehouseNotFound(warehouseId);
        }
        return zones.findByWarehouseId(warehouseId).stream()
                .map(WarehouseLayoutServiceImpl::toSummary)
                .toList();
    }

    /**
     * One query per table, each filtered by the warehouse, put together in memory. Not locked, and
     * not one snapshot: under {@code READ COMMITTED} each query sees what was committed when it ran,
     * so a shelf created between two of them may come without its levels (a level or bin whose parent
     * was not read is dropped, never shown floating). The next read is whole; a map is redrawn often.
     */
    @Override
    @Transactional(readOnly = true)
    public WarehouseLayout layoutOf(UUID warehouseId) {
        var warehouse = layoutRead.findWarehouse(warehouseId).orElseThrow(() -> warehouseNotFound(warehouseId));
        return WarehouseLayout.assemble(warehouse, layoutRead.findZones(warehouseId),
                layoutRead.findShelves(warehouseId), layoutRead.findLevels(warehouseId),
                layoutRead.findBins(warehouseId), layoutRead.findAreas(warehouseId),
                layoutRead.findBoundaries(warehouseId));
    }

    /** Another zone of the same warehouse with this name; the zone itself keeping its name is fine. */
    private void requireNameFree(Zone zone) {
        zones.findByWarehouseIdAndName(zone.warehouseId(), zone.name())
                .filter(other -> !other.id().equals(zone.id()))
                .ifPresent(other -> {
                    throw new ConflictException(ErrorCode.ZONE_NAME_ALREADY_EXISTS,
                            "The warehouse already has a zone named " + zone.name());
                });
    }

    private Warehouse require(UUID warehouseId) {
        return warehouses.findById(warehouseId).orElseThrow(() -> warehouseNotFound(warehouseId));
    }

    private static BusinessException warehouseNotFound(UUID warehouseId) {
        return new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse " + warehouseId + " not found");
    }

    private static WarehouseSummary toSummary(Warehouse warehouse) {
        return new WarehouseSummary(warehouse.id(), warehouse.prefix(), warehouse.name(),
                warehouse.address(), warehouse.returnAddress(), warehouse.mapUnit(),
                warehouse.mapWidth(), warehouse.mapHeight(), warehouse.status(), warehouse.version(),
                warehouse.createdAt());
    }

    private static ZoneSummary toSummary(Zone zone) {
        return new ZoneSummary(zone.id(), zone.warehouseId(), zone.name(), zone.color(), zone.version());
    }
}
