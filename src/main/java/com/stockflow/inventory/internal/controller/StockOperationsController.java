package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.internal.controller.dto.MoveStockPackagesRequest;
import com.stockflow.inventory.internal.controller.dto.MovedStockPackageResponse;
import com.stockflow.inventory.internal.controller.dto.RejectAdjustmentRequest;
import com.stockflow.inventory.internal.controller.dto.RequestAdjustmentRequest;
import com.stockflow.inventory.internal.controller.dto.StockAdjustmentResponse;
import com.stockflow.inventory.internal.controller.dto.StockMovementResponse;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.service.StockOperations;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock moves, the stock ledger and stock adjustments (SCRUM-424, SCRUM-145).
 *
 * <p>Three permissions, on purpose: moving stock and asking for a correction are floor work
 * ({@code inventory-stock-items:UPDATE}, held by warehouse staff); reading the ledger is
 * {@code inventory-stock-movements:READ}; deciding a correction is
 * {@code inventory-stock-adjustments:APPROVE}, which only the warehouse manager holds, and the
 * person who asked can never decide it (four eyes, enforced in the aggregate and the table). The
 * person who asked may withdraw it while it is pending, under the same permission they asked with.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory operations", description = "Carton moves between locations, stock history, adjustments")
@PermissionResource(
        code = InventoryResources.STOCK_MOVEMENTS,
        group = "Inventory",
        label = "Stock history",
        route = "/inventory/movements",
        apiPath = "/api/v1/inventory/movements",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class StockOperationsController {

    private final StockOperations operations;
    private final StockOperationsWebMapper mapper;

    StockOperationsController(StockOperations operations, StockOperationsWebMapper mapper) {
        this.operations = operations;
        this.mapper = mapper;
    }

    /**
     * Kltn-docs 11: cartons scanned by LPN to one destination, all of them or none (BR-04, BR-07); never a carton held
     * for an order (BR-05) or one a cycle count holds (BR-06). The request id names the move in the ledger
     * ({@code MOVE_TASK}), so sending it again answers the cartons it moved instead of moving them twice.
     */
    @PostMapping("/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Move whole cartons, by LPN, to one location; all or none, idempotent on requestId",
            description = "404 STOCK_PACKAGE_NOT_FOUND; 409 STOCK_PACKAGE_HELD for a carton held for an order, "
                    + "STOCK_SLOT_UNDER_COUNT, INVALID_STOCK_PACKAGE_TRANSITION for a carton not in stock, "
                    + "MOVE_ACROSS_WAREHOUSES; 422 VALIDATION_FAILED for a carton already there; 403 for a carton or "
                    + "location in another warehouse.")
    @RequiresPermission(resource = InventoryResources.STOCK_ITEMS, action = Action.UPDATE)
    public ApiResponse<List<MovedStockPackageResponse>> move(@Valid @RequestBody MoveStockPackagesRequest request,
                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(operations.moveStockPackages(new MoveStockPackagesCommand(request.lpns(),
                request.toLocation(), MoveReference.MOVE_TASK, request.requestId(), user.userId())).stream()
                .map(mapper::toResponse).toList());
    }

    @GetMapping("/movements")
    @Operation(summary = "Stock history, newest first; location matches either side of a line")
    @RequiresPermission(resource = InventoryResources.STOCK_MOVEMENTS, action = Action.READ)
    public ApiResponse<PageResponse<StockMovementResponse>> movements(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) StockMovement.MovementType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser Optional<CurrentUser> user) {
        return ApiResponse.ok(operations.ledger(sku, location, type, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, BlindCount.visibilityFor(user))
                .map(mapper::toResponse));
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask for a stock correction with a reason; nothing changes until someone else approves",
            description = "Loose stock by locationCode, sku and lotNumber, or a whole carton by lpn (written off "
                    + "whole: quantityDelta is minus what it holds; a quarantined carton that cannot be used is "
                    + "written off as DAMAGED). An lpn with an open putaway task is 409 STOCK_PACKAGE_DECISION_PENDING.")
    @RequiresPermission(resource = InventoryResources.STOCK_ITEMS, action = Action.UPDATE)
    public ApiResponse<StockAdjustmentResponse> requestAdjustment(@Valid @RequestBody RequestAdjustmentRequest request,
                                                                  @AuthenticatedUser CurrentUser user) {
        boolean byLpn = request.lpn() != null && !request.lpn().isBlank();
        return ApiResponse.ok(mapper.toResponse(operations.requestAdjustment(new RequestAdjustmentCommand(
                request.locationCode(), request.sku() == null || request.sku().isBlank() ? null : new Sku(request.sku()),
                byLpn ? null : request.lotNumber(), request.quantityDelta(), request.reason(), request.note(),
                user.userId(), null, byLpn ? request.lpn() : null))));
    }

    @GetMapping("/adjustments")
    @Operation(summary = "Stock adjustments, newest first; status=PENDING_APPROVAL is the approver's queue")
    @RequiresPermission(resource = InventoryResources.STOCK_ADJUSTMENTS, action = Action.READ)
    public ApiResponse<PageResponse<StockAdjustmentResponse>> adjustments(
            @RequestParam(required = false) StockAdjustmentStatus status,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String location,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser Optional<CurrentUser> user) {
        return ApiResponse.ok(operations.adjustments(status, sku, location, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort, BlindCount.visibilityFor(user))
                .map(mapper::toResponse));
    }

    @GetMapping("/adjustments/{adjustmentId}")
    @Operation(summary = "One stock adjustment")
    @RequiresPermission(resource = InventoryResources.STOCK_ADJUSTMENTS, action = Action.READ)
    public ApiResponse<StockAdjustmentResponse> adjustment(@PathVariable UUID adjustmentId,
                                                           @AuthenticatedUser Optional<CurrentUser> user) {
        return operations.findAdjustment(adjustmentId, BlindCount.visibilityFor(user)).map(mapper::toResponse)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ADJUSTMENT_NOT_FOUND,
                        "No stock adjustment " + adjustmentId));
    }

    @PostMapping("/adjustments/{adjustmentId}/approval")
    @Operation(summary = "Approve and post a stock adjustment; the requester cannot approve their own")
    @RequiresPermission(resource = InventoryResources.STOCK_ADJUSTMENTS, action = Action.APPROVE)
    public ApiResponse<StockAdjustmentResponse> approve(@PathVariable UUID adjustmentId,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(operations.approve(adjustmentId, user.userId())));
    }

    @PostMapping("/adjustments/{adjustmentId}/rejection")
    @Operation(summary = "Reject a stock adjustment with a reason")
    @RequiresPermission(resource = InventoryResources.STOCK_ADJUSTMENTS, action = Action.APPROVE)
    public ApiResponse<StockAdjustmentResponse> reject(@PathVariable UUID adjustmentId,
                                                       @Valid @RequestBody RejectAdjustmentRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(operations.reject(adjustmentId, user.userId(), request.reason())));
    }

    @PostMapping("/adjustments/{adjustmentId}/withdrawal")
    @Operation(summary = "Withdraw your own stock adjustment while it is still pending")
    @RequiresPermission(resource = InventoryResources.STOCK_ITEMS, action = Action.UPDATE)
    public ApiResponse<StockAdjustmentResponse> withdraw(@PathVariable UUID adjustmentId,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(operations.withdraw(adjustmentId, user.userId())));
    }
}
