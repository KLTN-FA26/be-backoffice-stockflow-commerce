package com.stockflow.warehouse.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.internal.domain.Area;
import com.stockflow.warehouse.internal.domain.AreaRepository;
import com.stockflow.warehouse.internal.domain.CodePart;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.Placement;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The transaction boundary for areas. BR-06 and BR-07 have no database twin, for the reason
 * {@code ShelfLayoutServiceImpl} gives; the warehouse row lock taken first in every write is their
 * second line of defence (issue #18 D4).
 *
 * <p>Every change is {@link Auditable}: a new area under its warehouse, since its id is minted inside
 * the call, and every later change under the area's own id. Placing or editing an area records
 * only what succeeded, for the reason {@code ShelfLayoutServiceImpl} gives; a refused status change
 * is still recorded.</p>
 */
@Service
@Transactional
class AreaLayoutServiceImpl implements AreaLayoutService {

    private final AreaRepository areas;
    private final WarehouseRepository warehouses;

    AreaLayoutServiceImpl(AreaRepository areas, WarehouseRepository warehouses) {
        this.areas = areas;
        this.warehouses = warehouses;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "area",
            resourceId = "#command.warehouseId()", includeFailures = false)
    public AreaSummary createArea(AreaCommands.CreateArea command) {
        Warehouse warehouse = WarehouseLocks.lock(warehouses, command.warehouseId());
        String code = CodePart.of(command.code(), CodePart.CODE_MAX_LENGTH).value();
        if (areas.existsByWarehouseIdAndCode(warehouse.id(), code)) {
            throw new ConflictException(ErrorCode.AREA_CODE_ALREADY_EXISTS,
                    "Warehouse %s already has area %s".formatted(warehouse.prefix(), code));
        }
        Area area = Area.create(Identifiers.newId(), warehouse.id(), code, command.details(), warehouse.prefix(),
                Identifiers::newId);
        requirePlaceable(warehouse, area.id(), area.footprint());
        return AreaSummary.of(areas.save(area));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "area",
            resourceId = "#command.areaId()", includeFailures = false)
    public AreaSummary updateArea(AreaCommands.UpdateArea command) {
        Warehouse warehouse = lockWarehouseOf(command.areaId());
        Area area = require(command.areaId());
        area.update(command.details(), command.expectedVersion(), warehouse.prefix(), Identifiers::newId);
        if (area.holdsPlace()) {
            requirePlaceable(warehouse, area.id(), area.footprint());
        }
        return AreaSummary.of(areas.save(area));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "area", resourceId = "#areaId")
    public AreaSummary changeAreaStatus(UUID areaId, LocationStatus status) {
        Warehouse warehouse = lockWarehouseOf(areaId);
        Area area = require(areaId);
        if (!area.holdsPlace() && status != null && status != LocationStatus.INACTIVE) {
            requirePlaceable(warehouse, area.id(), area.footprint());
        }
        area.changeStatus(status);
        return AreaSummary.of(areas.save(area));
    }

    /** BR-06 against the frame, BR-07 against every shelf and area on the layout but this one. */
    private void requirePlaceable(Warehouse warehouse, UUID self, Footprint footprint) {
        warehouse.requireOnMap(footprint);
        Placement.requireClear(footprint, self, warehouses.findPlacements(warehouse.id()));
    }

    /** Locks before reading the area, so the area read is not stale; see {@code ShelfLayoutServiceImpl}. */
    private Warehouse lockWarehouseOf(UUID areaId) {
        return WarehouseLocks.lockOwner(warehouses, areas.findWarehouseIdOf(areaId), () -> areaNotFound(areaId));
    }

    private Area require(UUID areaId) {
        return areas.findById(areaId).orElseThrow(() -> areaNotFound(areaId));
    }

    private static BusinessException areaNotFound(UUID areaId) {
        return new BusinessException(ErrorCode.AREA_NOT_FOUND, "Area " + areaId + " not found");
    }
}
