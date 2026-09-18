package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SupplierPerformanceSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import com.stockflow.procurement.internal.controller.dto.SaveSupplierRequest;
import com.stockflow.procurement.internal.controller.dto.SupplierPerformanceResponse;
import com.stockflow.procurement.internal.controller.dto.SupplierResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/suppliers")
@Tag(name = "Suppliers", description = "Supplier profiles, commercial defaults and performance")
@PermissionResource(code = "procurement-suppliers", group = "Procurement", label = "Suppliers",
        route = "/admin/suppliers", apiPath = "/api/v1/suppliers",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class SupplierController {
    private final SupplierService service;
    SupplierController(SupplierService service) { this.service = service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a supplier profile")
    @RequiresPermission(resource = "procurement-suppliers", action = Action.CREATE)
    public ApiResponse<SupplierResponse> create(@Valid @RequestBody SaveSupplierRequest request) {
        return ApiResponse.ok(response(service.create(command(request))));
    }

    @PutMapping("/{supplierId}")
    @Operation(summary = "Update supplier contacts, status and commercial defaults")
    @RequiresPermission(resource = "procurement-suppliers", action = Action.UPDATE)
    public ApiResponse<SupplierResponse> update(@PathVariable UUID supplierId,
                                                 @Valid @RequestBody SaveSupplierRequest request) {
        return ApiResponse.ok(response(service.update(supplierId, command(request))));
    }

    @DeleteMapping("/{supplierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a supplier; retained for purchasing history")
    @RequiresPermission(resource = "procurement-suppliers", action = Action.DELETE)
    public void deactivate(@PathVariable UUID supplierId) { service.deactivate(supplierId); }

    @GetMapping("/{supplierId}")
    @RequiresPermission(resource = "procurement-suppliers", action = Action.READ)
    public ApiResponse<SupplierResponse> findOne(@PathVariable UUID supplierId) {
        return service.findById(supplierId).map(SupplierController::response).map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + supplierId));
    }

    @GetMapping
    @RequiresPermission(resource = "procurement-suppliers", action = Action.READ)
    public ApiResponse<PageResponse<SupplierResponse>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.list(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, search, status, sort)
                .map(SupplierController::response));
    }

    @GetMapping("/{supplierId}/performance")
    @Operation(summary = "Calculate supplier delivery and quality performance")
    @RequiresPermission(resource = "procurement-suppliers", action = Action.READ)
    public ApiResponse<SupplierPerformanceResponse> performance(@PathVariable UUID supplierId) {
        SupplierPerformanceSummary s = service.performance(supplierId);
        return ApiResponse.ok(new SupplierPerformanceResponse(s.supplierId(), s.totalPurchaseOrders(),
                s.fulfilledPurchaseOrders(), s.onTimeOrders(), s.lateOrders(), s.onTimeDeliveryRate(),
                s.averageLeadTimeDays(), s.acceptedQuantity(), s.rejectedQuantity(),
                s.qualityAcceptanceRate(), s.calculatedAt()));
    }

    private static SaveSupplierCommand command(SaveSupplierRequest r) { return new SaveSupplierCommand(r.code(), r.name(),
            r.contactName(), r.email(), r.phone(), r.taxCode(), r.status(), r.paymentTermDays(), r.leadTimeDays(),
            r.communicationChannel(), r.apiEndpoint()); }
    private static SupplierResponse response(SupplierSummary s) { return new SupplierResponse(s.supplierId(), s.code(), s.name(),
            s.contactName(), s.email(), s.phone(), s.taxCode(), s.status(), s.paymentTermDays(), s.leadTimeDays(),
            s.communicationChannel(), s.apiEndpoint(), s.createdAt(), s.lastModifiedAt()); }
}
