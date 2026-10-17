package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CreateSalesUnitRequest;
import com.stockflow.product.internal.controller.dto.SalesUnitResponse;
import com.stockflow.product.internal.controller.dto.UpdateSalesUnitRequest;
import com.stockflow.product.internal.service.SkuUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The units a variant is sold in and their prices (docs 01 §3 "Đơn vị bán", BR-11): chosen only among
 * its inventory item's units; with none listed, the base unit at the catalog base price. Same
 * permissions as the SKU's logistics.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/sales-units")
@Tag(name = "Variant sales units", description = "Units a variant is sold in, with a price per unit")
class SalesUnitController {

    private final SkuUnitService service;
    private final SkuUnitWebMapper mapper;

    SalesUnitController(SkuUnitService service, SkuUnitWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The variant's sales units; with none listed, one implicit entry for the base unit")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<SalesUnitResponse>> list(@PathVariable UUID productId,
                                                             @PathVariable UUID variantId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.salesUnits(productId, variantId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{salesUnitId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<SalesUnitResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId,
                                              @PathVariable UUID salesUnitId) {
        return ApiResponse.ok(mapper.toResponse(service.salesUnit(productId, variantId, salesUnitId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Sell the variant in one of its item's units",
            description = "A unit of another SKU: 404 UOM_CONVERSION_NOT_FOUND; a retired unit or a price that "
                    + "breaks the rule (required, whole VND, for a packing unit; absent for the base unit): 400 "
                    + "VALIDATION_FAILED; already listed: 409 SALES_UNIT_DUPLICATE.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SalesUnitResponse> add(@PathVariable UUID productId, @PathVariable UUID variantId,
                                              @Valid @RequestBody CreateSalesUnitRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                service.addSalesUnit(productId, variantId, request.uomConversionId(), request.price())));
    }

    @PutMapping("/{salesUnitId}")
    @Operation(summary = "Reprice a sales unit; stale version: 409 OPTIMISTIC_LOCK")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SalesUnitResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                 @PathVariable UUID salesUnitId,
                                                 @Valid @RequestBody UpdateSalesUnitRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                service.changeSalesUnit(productId, variantId, salesUnitId, request.version(), request.price())));
    }

    @DeleteMapping("/{salesUnitId}")
    @Operation(summary = "Stop selling in a unit; removing the last one brings back the base-unit default")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<Void> delete(@PathVariable UUID productId, @PathVariable UUID variantId,
                                    @PathVariable UUID salesUnitId,
                                    @Parameter(description = "The version from the last read")
                                    @RequestParam long version) {
        service.removeSalesUnit(productId, variantId, salesUnitId, version);
        return ApiResponse.ok();
    }
}
