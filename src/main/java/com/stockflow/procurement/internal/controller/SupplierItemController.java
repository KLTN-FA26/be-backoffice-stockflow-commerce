package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SkuSupplierSummary;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierItemSummary;
import com.stockflow.procurement.internal.controller.dto.SaveSupplierItemRequest;
import com.stockflow.procurement.internal.controller.dto.SkuSupplierResponse;
import com.stockflow.procurement.internal.controller.dto.SupplierItemResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The supplier catalog. The permission resource is {@code procurement-suppliers}, declared on
 * {@link SupplierController}: a catalog row is part of the supplier's profile, so reading needs READ and
 * every edit needs UPDATE.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Supplier catalog", description = "Which supplier sells which SKU, at what price and terms")
class SupplierItemController {
    private final SupplierItemService service;

    SupplierItemController(SupplierItemService service) {
        this.service = service;
    }

    @GetMapping("/suppliers/{supplierId}/items")
    @Operation(summary = "List the SKUs a supplier sells")
    @RequiresPermission(resource = SupplierResources.SUPPLIERS, action = Action.READ)
    public ApiResponse<PageResponse<SupplierItemResponse>> list(
            @PathVariable UUID supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(
                service.list(supplierId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                        .map(SupplierItemController::response));
    }

    @PostMapping("/suppliers/{supplierId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "List a SKU for an active supplier")
    @RequiresPermission(resource = SupplierResources.SUPPLIERS, action = Action.UPDATE)
    public ApiResponse<SupplierItemResponse> create(
            @PathVariable UUID supplierId,
            @Validated(SaveSupplierItemRequest.Create.class) @RequestBody SaveSupplierItemRequest request) {
        return ApiResponse.ok(response(service.create(supplierId, command(request))));
    }

    @PutMapping("/suppliers/{supplierId}/items/{supplierItemId}")
    @Operation(summary = "Replace the terms of a catalog row; a new price opens a new price period")
    @RequiresPermission(resource = SupplierResources.SUPPLIERS, action = Action.UPDATE)
    public ApiResponse<SupplierItemResponse> update(
            @PathVariable UUID supplierId,
            @PathVariable UUID supplierItemId,
            @Validated(SaveSupplierItemRequest.Update.class) @RequestBody SaveSupplierItemRequest request) {
        return ApiResponse.ok(response(service.update(supplierId, supplierItemId, command(request))));
    }

    @DeleteMapping("/suppliers/{supplierId}/items/{supplierItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a catalog row unless an open purchase order still buys it")
    @RequiresPermission(resource = SupplierResources.SUPPLIERS, action = Action.UPDATE)
    public void delete(
            @PathVariable UUID supplierId,
            @PathVariable UUID supplierItemId,
            @RequestParam long version) {
        service.delete(supplierId, supplierItemId, version);
    }

    @GetMapping("/procurement/skus/{sku}/suppliers")
    @Operation(summary = "Every supplier that sells a SKU, preferred first, then by supplier name")
    @RequiresPermission(resource = SupplierResources.SUPPLIERS, action = Action.READ)
    public ApiResponse<List<SkuSupplierResponse>> suppliersOf(@PathVariable String sku) {
        return ApiResponse.ok(
                service.suppliersOf(sku).stream().map(SupplierItemController::response).toList());
    }

    private static SaveSupplierItemCommand command(SaveSupplierItemRequest r) {
        return new SaveSupplierItemCommand(
                r.sku(),
                r.supplierSkuCode(),
                r.price(),
                r.moq(),
                r.packSize(),
                r.leadTimeDays(),
                Boolean.TRUE.equals(r.preferred()),
                r.version());
    }

    private static SupplierItemResponse response(SupplierItemSummary s) {
        return new SupplierItemResponse(
                s.supplierItemId(),
                s.supplierId(),
                s.inventoryItemId(),
                s.sku(),
                s.supplierSkuCode(),
                s.price(),
                s.currency(),
                s.moq(),
                s.packSize(),
                s.leadTimeDays(),
                s.effectiveLeadTimeDays(),
                s.preferred(),
                s.version(),
                s.createdAt(),
                s.lastModifiedAt());
    }

    private static SkuSupplierResponse response(SkuSupplierSummary s) {
        return new SkuSupplierResponse(
                s.supplierId(),
                s.supplierCode(),
                s.supplierName(),
                s.supplierStatus(),
                s.supplierItemId(),
                s.supplierSkuCode(),
                s.price(),
                s.currency(),
                s.moq(),
                s.packSize(),
                s.effectiveLeadTimeDays(),
                s.preferred());
    }
}
