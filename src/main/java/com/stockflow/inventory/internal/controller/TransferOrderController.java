package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.CreateTransferOrderRequest;
import com.stockflow.inventory.internal.controller.dto.DispatchTransferRequest;
import com.stockflow.inventory.internal.controller.dto.ReasonRequest;
import com.stockflow.inventory.internal.controller.dto.TransferOrderResponse;
import com.stockflow.inventory.internal.controller.dto.TransferOrderRowResponse;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.service.TransferOrders;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inter-warehouse transfer orders (SCRUM-326/327). One verb per transition, so each carries its own
 * permission: approving needs {@code APPROVE}, which the planner who creates a transfer does not hold.
 */
@RestController
@RequestMapping("/api/v1/inventory/transfer-orders")
@Tag(name = "Transfer orders", description = "Stock moved between warehouses")
@PermissionResource(
        code = InventoryResources.TRANSFER_ORDERS,
        group = "Inventory",
        label = "Transfer orders",
        route = "/inventory/transfers",
        apiPath = "/api/v1/inventory/transfer-orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class TransferOrderController {

    private final TransferOrders transfers;
    private final TransferOrderWebMapper mapper;

    TransferOrderController(TransferOrders transfers, TransferOrderWebMapper mapper) {
        this.transfers = transfers;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a transfer order (DRAFT); every line must be available at the source now")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.CREATE)
    public ApiResponse<TransferOrderResponse> create(@Valid @RequestBody CreateTransferOrderRequest request) {
        return ApiResponse.ok(mapper.toResponse(transfers.create(new TransferOrders.Create(request.fromWarehouseId(),
                request.toWarehouseId(), request.reason(), request.expectedDate(),
                request.lines().stream().map(l -> new TransferOrders.NewLine(l.sku(), l.lotNumber(), l.quantity()))
                        .toList()))));
    }

    @GetMapping
    @Operation(summary = "Transfer orders, newest first")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<TransferOrderRowResponse>> list(
            @RequestParam(name = "status", required = false) List<TransferStatus> statuses,
            @RequestParam(required = false) UUID fromWarehouseId,
            @RequestParam(required = false) UUID toWarehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(transfers.list(statuses, fromWarehouseId, toWarehouseId, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "One transfer order with its lines")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.READ)
    public ApiResponse<TransferOrderResponse> get(@PathVariable UUID transferId) {
        return ApiResponse.ok(mapper.toResponse(transfers.get(transferId)));
    }

    @PostMapping("/{transferId}/submission")
    @Operation(summary = "Submit: approved at once at or under the threshold, otherwise PENDING_APPROVAL")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.UPDATE)
    public ApiResponse<TransferOrderResponse> submit(@PathVariable UUID transferId,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(transfers.submit(transferId, user.userId())));
    }

    @PostMapping("/{transferId}/approval")
    @Operation(summary = "Approve; the submitter cannot approve their own")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.APPROVE)
    public ApiResponse<TransferOrderResponse> approve(@PathVariable UUID transferId,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(transfers.approve(transferId, user.userId())));
    }

    @PostMapping("/{transferId}/rejection")
    @Operation(summary = "Reject back to DRAFT, with a reason")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.APPROVE)
    public ApiResponse<TransferOrderResponse> reject(@PathVariable UUID transferId,
                                                       @Valid @RequestBody ReasonRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(transfers.reject(transferId, user.userId(), request.reason())));
    }

    @PostMapping("/{transferId}/picking")
    @Operation(summary = "Start picking at the source")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.UPDATE)
    public ApiResponse<TransferOrderResponse> startPicking(@PathVariable UUID transferId) {
        return ApiResponse.ok(mapper.toResponse(transfers.startPicking(transferId)));
    }

    @PostMapping("/{transferId}/picking-cancellation")
    @Operation(summary = "Stop picking; back to APPROVED")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.UPDATE)
    public ApiResponse<TransferOrderResponse> cancelPicking(@PathVariable UUID transferId) {
        return ApiResponse.ok(mapper.toResponse(transfers.cancelPicking(transferId)));
    }

    @PostMapping("/{transferId}/dispatch")
    @Operation(summary = "Dispatch from the source: stock leaves it and is in transit, owned by the transfer")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.UPDATE)
    public ApiResponse<TransferOrderResponse> dispatch(@PathVariable UUID transferId,
                                                         @Valid @RequestBody DispatchTransferRequest request,
                                                         @AuthenticatedUser CurrentUser user) {
        Map<UUID, Integer> shipped = new LinkedHashMap<>();
        request.lines().forEach(line -> shipped.merge(line.lineId(), line.shippedQuantity(), Integer::sum));
        return ApiResponse.ok(mapper.toResponse(transfers.dispatch(transferId, user.userId(), shipped)));
    }

    @PostMapping("/{transferId}/cancellation")
    @Operation(summary = "Cancel; only before dispatch (BR-04)")
    @RequiresPermission(resource = InventoryResources.TRANSFER_ORDERS, action = Action.UPDATE)
    public ApiResponse<TransferOrderResponse> cancel(@PathVariable UUID transferId,
                                                       @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(mapper.toResponse(transfers.cancel(transferId, request.reason())));
    }
}
