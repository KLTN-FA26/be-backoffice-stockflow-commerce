package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.StockLoads;
import com.stockflow.order.internal.domain.CapacityBoard;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.warehouse.api.CapacityLayout;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The warehouse capacity board (kltn-docs 06 §4.6; SCRUM-669), read by the warehouse manager and, through SCRUM-673's
 * warnings, by Sales, the Sales Manager and the production coordinator.
 *
 * <p><b>Why a board about a warehouse is served by {@code order}.</b> It needs three modules' facts: bins and their
 * volume ({@code warehouse}), the cartons standing there and held for orders ({@code inventory}), and the cartons still
 * due on production orders ({@code order}). A module reads only its own schema (ADR-0005), so the facts are composed
 * through each module's {@code api}, and {@code order} is the one module allowed to call both other {@code api}s - and
 * the one whose services will warn from the board. {@code reporting} would not do: it depends on {@code order}, so the
 * warnings would close a cycle.</p>
 *
 * <p>All arithmetic is {@link CapacityBoard}'s; this class only gathers the facts, in one read-only transaction. Not a
 * snapshot: each read sees what was committed when it ran, which a board redrawn on every visit can afford.</p>
 */
@Service
@Transactional(readOnly = true)
public class CapacityBoardService {

    private final WarehouseService warehouses;
    private final StockLoads stock;
    private final InventoryControlService inventoryControl;
    private final ProductionOrderRepository productionOrders;

    CapacityBoardService(WarehouseService warehouses, StockLoads stock, InventoryControlService inventoryControl,
                         ProductionOrderRepository productionOrders) {
        this.warehouses = warehouses;
        this.stock = stock;
        this.inventoryControl = inventoryControl;
        this.productionOrders = productionOrders;
    }

    /**
     * @throws BusinessException {@code WAREHOUSE_NOT_FOUND} for an unknown warehouse; out of scope (403) for a caller
     *         limited to other warehouses
     */
    public CapacityBoard boardOf(UUID warehouseId) {
        WarehouseScope.requireWarehouse(warehouseId);
        CapacityLayout layout = warehouses.capacityLayout(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "Warehouse " + warehouseId + " not found"));

        List<String> codes = new ArrayList<>();
        layout.bins().forEach(bin -> codes.add(bin.locationCode()));
        layout.areas().forEach(area -> codes.add(area.locationCode()));
        Map<String, CapacityBoard.Load> loads = new HashMap<>();
        stock.loadAt(codes).forEach((code, load) -> loads.put(code, of(load)));

        List<CapacityBoard.Due> due = productionOrders.dueAt(warehouseId).stream()
                .map(d -> new CapacityBoard.Due(d.sku(), awaitingApproval(d.status()), d.cartons()))
                .toList();
        Map<String, CapacityBoard.CartonSize> sizes = new HashMap<>();
        inventoryControl.cartonSpecs(due.stream().map(d -> new Sku(d.sku())).distinct().toList())
                .forEach((sku, spec) -> sizes.put(sku.code(), sizeOf(spec)));

        return CapacityBoard.compute(new CapacityBoard.Inputs(layout.warehouseId(), layout.prefix(),
                layout.utilizationFactor(),
                layout.bins().stream().map(b -> new CapacityBoard.BinSpace(b.locationCode(), b.zoneId(),
                        b.zoneName(), b.shelfCode(), b.volumeM3(), b.maxWeightKg(), b.usable())).toList(),
                layout.areas().stream().map(a -> new CapacityBoard.AreaSpace(a.locationCode(), a.code(), a.name(),
                        CapacityBoard.AreaKind.valueOf(a.type().name()))).toList(),
                loads, of(stock.outboundAt(layout.prefix())), due, sizes));
    }

    private static boolean awaitingApproval(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.DRAFT || status == ProductionOrderStatus.PENDING_APPROVAL;
    }

    private static CapacityBoard.CartonSize sizeOf(CartonSpec spec) {
        return CapacityBoard.CartonSize.ofCentimetres(spec.lengthCm(), spec.widthCm(), spec.heightCm(),
                spec.weightKg());
    }

    private static CapacityBoard.Load of(StockLoads.Load load) {
        return new CapacityBoard.Load(load.cartons(), load.volumeM3(), load.weightKg(), load.unmeasuredCartons());
    }
}
