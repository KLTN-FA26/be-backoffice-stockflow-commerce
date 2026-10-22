package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CreateUomConversionRequest;
import com.stockflow.product.internal.controller.dto.UomConversionResponse;
import com.stockflow.product.internal.controller.dto.UpdateUomConversionRequest;
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
 * The units of measure of a SKU's inventory item: its base unit and its packing units with their factor
 * to the base unit, size and weight, and which one purchase orders start in (docs 01 §3, BR-09, BR-10).
 * Same permissions as the SKU's logistics.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/uom-conversions")
@Tag(name = "SKU units of measure", description = "Base unit and packing units (UoM conversions) of a SKU")
class UomConversionController {

    private final SkuUnitService service;
    private final SkuUnitWebMapper mapper;

    UomConversionController(SkuUnitService service, SkuUnitWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The SKU's units: the base unit first, then the packing units by code")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<UomConversionResponse>> list(@PathVariable UUID productId,
                                                                 @PathVariable UUID variantId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.conversions(productId, variantId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{conversionId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<UomConversionResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                  @PathVariable UUID conversionId) {
        return ApiResponse.ok(mapper.toResponse(service.conversion(productId, variantId, conversionId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Declare a packing unit",
            description = "409 UOM_CONVERSION_DUPLICATE when the code is taken (the base unit's included).")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<UomConversionResponse> add(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                  @Valid @RequestBody CreateUomConversionRequest request) {
        return ApiResponse.ok(mapper.toResponse(service.addConversion(productId, variantId, mapper.toDraft(request))));
    }

    @PutMapping("/{conversionId}")
    @Operation(summary = "Replace a unit; on the base unit only defaultPurchase changes",
            description = "Unmarking the default purchase unit hands it back to the base unit. Marking "
                    + "defaultShipping unmarks the item's previous default shipping unit atomically (no 409); "
                    + "unmarking it leaves the item without one. Retiring "
                    + "(active=false) a unit the variant sells in: 409 UOM_CONVERSION_IN_USE. Stale version: "
                    + "409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<UomConversionResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                     @PathVariable UUID conversionId,
                                                     @Valid @RequestBody UpdateUomConversionRequest request) {
        return ApiResponse.ok(mapper.toResponse(service.changeConversion(productId, variantId, conversionId,
                request.version(), mapper.toDraft(request))));
    }

    @DeleteMapping("/{conversionId}")
    @Operation(summary = "Delete a packing unit",
            description = "The base unit, or a unit the variant sells in: 409 UOM_CONVERSION_IN_USE.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<Void> delete(@PathVariable UUID productId, @PathVariable UUID variantId,
                                    @PathVariable UUID conversionId,
                                    @Parameter(description = "The version from the last read")
                                    @RequestParam long version) {
        service.removeConversion(productId, variantId, conversionId, version);
        return ApiResponse.ok();
    }
}
