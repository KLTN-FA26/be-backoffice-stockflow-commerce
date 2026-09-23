package com.stockflow.procurement.internal.controller;

import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.internal.controller.dto.CancelPurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.CloseShortRequest;
import com.stockflow.procurement.internal.controller.dto.CreatePurchaseOrderRequest;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderStatusCountResponse;
import com.stockflow.procurement.internal.controller.dto.ReceiveGoodsRequest;
import com.stockflow.procurement.internal.controller.dto.SupplierSpendResponse;
import com.stockflow.procurement.internal.controller.dto.SupplierConfirmationRequest;
import com.stockflow.procurement.api.RecordSupplierConfirmationCommand;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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

    @PostMapping("/{purchaseOrderId}/approval")
    @Operation(summary = "Approve a draft purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.APPROVE)
    public ApiResponse<PurchaseOrderResponse> approve(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(mapper.toResponse(procurementService.approve(purchaseOrderId)));
    }

    @PostMapping("/{purchaseOrderId}/sending")
    @Operation(summary = "Queue an approved PO for supplier delivery",
            description = "Use the same Idempotency-Key on retries. A new request for an already SENT PO returns 409. Delivery is asynchronous; SENT does not mean supplier acceptance.")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> send(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(mapper.toResponse(procurementService.send(purchaseOrderId)));
    }

    @PostMapping("/{purchaseOrderId}/supplier-confirmation")
    @Operation(summary = "Record a supplier's confirmation or rejection")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> recordSupplierConfirmation(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody SupplierConfirmationRequest request) {
        return ApiResponse.ok(mapper.toResponse(procurementService.recordSupplierConfirmation(purchaseOrderId,
                new RecordSupplierConfirmationCommand(request.status(), request.supplierReference(), request.note()))));
    }

    @PostMapping("/{purchaseOrderId}/cancellation")
    @Operation(summary = "Cancel a purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> cancel(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody CancelPurchaseOrderRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                procurementService.cancel(purchaseOrderId, request.reason())));
    }

    @PostMapping("/{purchaseOrderId}/receipts")
    @Operation(summary = "Record goods received against a purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> receiveGoods(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody ReceiveGoodsRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                procurementService.receiveGoods(purchaseOrderId, mapper.toCommand(request))));
    }

    @PostMapping("/{purchaseOrderId}/closure-short")
    @Operation(summary = "Close a purchase order short, writing off the remaining open quantity")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<PurchaseOrderResponse> closeShort(@PathVariable UUID purchaseOrderId,
            @Valid @RequestBody CloseShortRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                procurementService.closeShort(purchaseOrderId, request.reason())));
    }

    @GetMapping("/reports/status-dashboard")
    @Operation(summary = "Count of purchase orders per status")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<List<PurchaseOrderStatusCountResponse>> statusDashboard() {
        return ApiResponse.ok(procurementService.statusDashboard().stream()
                .map(mapper::toResponse)
                .toList());
    }

    @GetMapping("/reports/supplier-spend")
    @Operation(summary = "Suppliers ranked by total spend, highest first")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<SupplierSpendResponse>> supplierSpend(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedAtFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedAtTo) {
        var query = new SupplierSpendReportQuery(
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, supplierId, expectedAtFrom, expectedAtTo);
        return ApiResponse.ok(procurementService.supplierSpend(query).map(mapper::toResponse));
    }
}
