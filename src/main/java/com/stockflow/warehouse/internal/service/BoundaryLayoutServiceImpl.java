package com.stockflow.warehouse.internal.service;

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
    public BoundarySummary createBoundary(BoundaryCommands.CreateBoundary command) {
        Warehouse warehouse = warehouses.findByIdForUpdate(command.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "Warehouse " + command.warehouseId() + " not found"));
        Boundary boundary = Boundary.create(Identifiers.newId(), warehouse.id(), command.type(), command.segment(),
                command.passable(), command.operationalStatus());
        warehouse.requireOnMap(boundary.segment());
        return BoundarySummary.of(boundaries.save(boundary));
    }

    @Override
    public BoundarySummary updateBoundary(BoundaryCommands.UpdateBoundary command) {
        Warehouse warehouse = lockWarehouseOf(command.boundaryId());
        Boundary boundary = require(command.boundaryId());
        boundary.update(command.type(), command.segment(), command.passable(), command.operationalStatus(),
                command.expectedVersion());
        warehouse.requireOnMap(boundary.segment());
        return BoundarySummary.of(boundaries.save(boundary));
    }

    @Override
    public void deleteBoundary(UUID boundaryId, long expectedVersion) {
        lockWarehouseOf(boundaryId);
        require(boundaryId).requireVersion(expectedVersion);
        boundaries.deleteById(boundaryId);
    }

    /** Locks before reading the boundary, so the read is not stale; see {@code ShelfLayoutServiceImpl}. */
    private Warehouse lockWarehouseOf(UUID boundaryId) {
        UUID warehouseId = boundaries.findWarehouseIdOf(boundaryId).orElseThrow(() -> boundaryNotFound(boundaryId));
        return warehouses.findByIdForUpdate(warehouseId).orElseThrow(() -> boundaryNotFound(boundaryId));
    }

    private Boundary require(UUID boundaryId) {
        return boundaries.findById(boundaryId).orElseThrow(() -> boundaryNotFound(boundaryId));
    }

    private static BusinessException boundaryNotFound(UUID boundaryId) {
        return new BusinessException(ErrorCode.BOUNDARY_NOT_FOUND, "Boundary " + boundaryId + " not found");
    }
}
