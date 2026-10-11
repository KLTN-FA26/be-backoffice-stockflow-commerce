package com.stockflow.warehouse.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.internal.domain.Boundary;
import com.stockflow.warehouse.internal.domain.BoundaryRepository;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The transaction boundary for walls and doors. The warehouse lock comes first in every write, as
 * for shelves and areas: shrinking the map reads how far boundaries reach (BR-06), and must not race
 * a boundary being drawn further out.
 *
 * <p>Every change is {@link Auditable}: a new boundary under its warehouse, since its id is minted
 * inside the call, and every later change under the boundary's own id. A delete matters most - it is
 * the one hard delete on the map (issue #18 D11), so the audit entry is all that is left of it, and a
 * refused delete is recorded too. Drawing or editing a boundary records only what succeeded, for the
 * reason {@code ShelfLayoutServiceImpl} gives.</p>
 */
@Service
@Transactional
class BoundaryLayoutServiceImpl implements BoundaryLayoutService {

    private final BoundaryRepository boundaries;
    private final WarehouseRepository warehouses;

    BoundaryLayoutServiceImpl(BoundaryRepository boundaries, WarehouseRepository warehouses) {
        this.boundaries = boundaries;
        this.warehouses = warehouses;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "boundary",
            resourceId = "#command.warehouseId()", includeFailures = false)
    public BoundarySummary createBoundary(BoundaryCommands.CreateBoundary command) {
        Warehouse warehouse = WarehouseLocks.lock(warehouses, command.warehouseId());
        Boundary boundary = Boundary.create(Identifiers.newId(), warehouse.id(), command.type(), command.segment(),
                command.passable(), command.operationalStatus());
        warehouse.requireOnMap(boundary.segment());
        return BoundarySummary.of(boundaries.save(boundary));
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "boundary",
            resourceId = "#command.boundaryId()", includeFailures = false)
    public BoundarySummary updateBoundary(BoundaryCommands.UpdateBoundary command) {
        Warehouse warehouse = lockWarehouseOf(command.boundaryId());
        Boundary boundary = require(command.boundaryId());
        boundary.update(command.type(), command.segment(), command.passable(), command.operationalStatus(),
                command.expectedVersion());
        warehouse.requireOnMap(boundary.segment());
        return BoundarySummary.of(boundaries.save(boundary));
    }

    @Override
    @Auditable(action = AuditAction.DELETE, resourceType = "boundary", resourceId = "#boundaryId")
    public void deleteBoundary(UUID boundaryId, long expectedVersion) {
        lockWarehouseOf(boundaryId);
        require(boundaryId).requireVersion(expectedVersion);
        boundaries.deleteById(boundaryId);
    }

    /** Locks before reading the boundary, so the read is not stale; see {@code ShelfLayoutServiceImpl}. */
    private Warehouse lockWarehouseOf(UUID boundaryId) {
        return WarehouseLocks.lockOwner(warehouses, boundaries.findWarehouseIdOf(boundaryId), () -> boundaryNotFound(boundaryId));
    }

    private Boundary require(UUID boundaryId) {
        return boundaries.findById(boundaryId).orElseThrow(() -> boundaryNotFound(boundaryId));
    }

    private static BusinessException boundaryNotFound(UUID boundaryId) {
        return new BusinessException(ErrorCode.BOUNDARY_NOT_FOUND, "Boundary " + boundaryId + " not found");
    }
}
