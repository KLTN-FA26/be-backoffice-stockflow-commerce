package com.stockflow.procurement.internal.controller;

import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.internal.controller.dto.CreatePurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderResponse;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
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
import java.util.UUID;

/**
 * HTTP entry point for purchase order creation (SCRUM-113/WBS 3.2.1).
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} is applied by a Spring AOP
 * proxy, which advises a non-public method only when the generated proxy happens to land in the
 * same package and classloader — when that does not hold, the guard is skipped silently.</p>
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@Tag(name = "Purchase Orders", description = "Procurement purchase orders")
@PermissionResource(
        code = PurchaseOrderResources.PURCHASE_ORDERS,
        group = "Procurement",
        label = "Purchase Orders",
        route = "/admin/purchase-orders",
        apiPath = "/api/v1/purchase-orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE, Action.EXPORT})
class PurchaseOrderController {

    private final ProcurementService procurementService;
    private final PurchaseOrderWebMapper mapper;

    PurchaseOrderController(ProcurementService procurementService, PurchaseOrderWebMapper mapper) {
        this.procurementService = procurementService;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.CREATE)
    public ApiResponse<PurchaseOrderResponse> create(
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                procurementService.createPurchaseOrder(mapper.toCommand(request))));
    }

    @GetMapping("/{purchaseOrderId}")
    @Operation(summary = "Look up one purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PurchaseOrderResponse> findOne(@PathVariable UUID purchaseOrderId) {
        return procurementService.findById(purchaseOrderId)
                .map(mapper::toResponse)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND,
                        "No purchase order with id " + purchaseOrderId));
    }

    @GetMapping
    @Operation(summary = "List purchase orders, paginated and filterable")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<PurchaseOrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String sort) {
        var query = new ListPurchaseOrdersQuery(
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, supplierId, status, sort);
        return ApiResponse.ok(procurementService.list(query).map(mapper::toResponse));
    }
}
