package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.InventoryControlResponse;
import com.stockflow.product.internal.controller.dto.UpdateInventoryControlRequest;
import com.stockflow.product.internal.service.SkuInventoryControlService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The stock policy of one SKU (reorder point, safety stock, removal strategy, tracking), held on its inventory item.
 * The canonical route is {@code /variants/{variantId}/inventory-control}; the older
 * {@code /skus/{skuId}/inventory-control} takes the same variant id, keeps working with the same permission and is
 * marked deprecated in the API description (see {@code SkuLogisticsController}).
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
class InventoryControlController {
    private final SkuInventoryControlService service;
    private final InventoryControlWebMapper mapper;

    InventoryControlController(
            SkuInventoryControlService service, InventoryControlWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/variants/{variantId}/inventory-control")
    @Operation(summary = "A variant's stock policy; an obsolete variant is still readable")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<InventoryControlResponse> get(
            @PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(service.get(productId, variantId)));
    }

    @PutMapping("/variants/{variantId}/inventory-control")
    @Operation(summary = "Replace a variant's stock policy; quote the version the last read returned",
            description = "An obsolete variant is refused with 409 VARIANT_OBSOLETE.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<InventoryControlResponse> update(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody UpdateInventoryControlRequest request) {
        return ApiResponse.ok(
                mapper.toResponse(
                        service.update(
                                productId, variantId, request.version(), mapper.toPolicy(request))));
    }

    @GetMapping("/skus/{skuId}/inventory-control")
    @Operation(summary = "Deprecated: use /variants/{variantId}/inventory-control (skuId is the variant id)",
            deprecated = true)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<InventoryControlResponse> getBySkuRoute(
            @PathVariable UUID productId, @PathVariable UUID skuId) {
        return get(productId, skuId);
    }

    @PutMapping("/skus/{skuId}/inventory-control")
    @Operation(summary = "Deprecated: use /variants/{variantId}/inventory-control (skuId is the variant id)",
            deprecated = true)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<InventoryControlResponse> updateBySkuRoute(
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            @Valid @RequestBody UpdateInventoryControlRequest request) {
        return update(productId, skuId, request);
    }
}
