package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.LotTraceResponse;
import com.stockflow.inventory.internal.domain.LotTrace;
import com.stockflow.inventory.internal.service.LotTraces;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The lot traceability report (SCRUM-153, WBS 3.6.2.3): supplier and goods receipt, then every ledger line, then
 * the allocations, picks and customer orders, with the balance left now. Read with the stock-history permission
 * ({@code inventory-stock-movements:READ}): it is the ledger, grouped by lot. A warehouse-bound caller sees their
 * own warehouses only.
 */
@RestController
@RequestMapping("/api/v1/inventory/lots")
@Tag(name = "Lot traceability", description = "Batch/lot tracking and the trace report")
class LotTraceController {

    private final LotTraces traces;

    LotTraceController(LotTraces traces) {
        this.traces = traces;
    }

    @GetMapping
    @Operation(summary = "Lots, most recently received first; by SKU, lot number, warehouse and the days they moved")
    @RequiresPermission(resource = InventoryResources.STOCK_MOVEMENTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<LotTraceResponse.Lot>> lots(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String lotNumber,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(traces.lots(new LotTraces.Query(sku, lotNumber, warehouseId, from, to, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)).map(LotTraceController::toResponse));
    }

    @GetMapping("/{lotId}")
    @Operation(summary = "One lot traced: origin, balances by location, allocations and holds, customer orders")
    @RequiresPermission(resource = InventoryResources.STOCK_MOVEMENTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<LotTraceResponse> trace(@PathVariable UUID lotId,
                                               @RequestParam(required = false) UUID warehouseId) {
        LotTrace trace = traces.trace(lotId, warehouseId);
        return ApiResponse.ok(new LotTraceResponse(toResponse(trace.lot()),
                trace.balances().stream().map(b -> new LotTraceResponse.Balance(b.locationCode(), b.status(),
                        b.onHand(), b.held())).toList(),
                trace.holds().stream().map(h -> new LotTraceResponse.Hold(h.reservationId(), h.orderId(),
                        h.productionOrderId(), h.allocatedFrom(), h.locationCode(), h.quantity(), h.status(),
                        h.reservedAt(), h.closedAt())).toList(),
                trace.orders().stream().map(o -> new LotTraceResponse.OrderLeg(o.orderId(), o.picked(), o.putBack(),
                        o.firstAt(), o.lastAt())).toList()));
    }

    @GetMapping("/{lotId}/movements")
    @Operation(summary = "The lot's stock ledger lines, oldest first")
    @RequiresPermission(resource = InventoryResources.STOCK_MOVEMENTS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<LotTraceResponse.Movement>> movements(
            @PathVariable UUID lotId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(traces.movements(lotId, warehouseId, from, to, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(m -> new LotTraceResponse.Movement(m.movementId(),
                m.type(), m.fromLocation(), m.toLocation(), m.quantity(), m.fromStatus(), m.toStatus(),
                m.referenceType(), m.referenceId(), m.orderId(), m.actorId(), m.occurredAt(), m.reason())));
    }

    private static LotTraceResponse.Lot toResponse(LotTrace.Summary l) {
        return new LotTraceResponse.Lot(l.lotId(), l.sku(), l.lotNumber(), l.expiryDate(), l.receivedAt(), l.origin(),
                l.goodsReceiptId(), l.goodsReceiptNumber(), l.purchaseOrderId(), l.purchaseOrderNumber(),
                l.supplierId(), l.supplierName(), l.receivingWarehouseId(), l.receivedQuantity(), l.onHand(),
                l.available(), l.held());
    }
}
