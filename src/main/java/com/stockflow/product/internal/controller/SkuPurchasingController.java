package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.SkuPurchasingRequest;
import com.stockflow.product.internal.controller.dto.SkuPurchasingResponse;
import com.stockflow.product.internal.service.SkuPurchasingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Default supplier, reference purchase price, lead time and MOQ of one SKU, held on its inventory item
 * (docs 01 §3 "Thông tin mua hàng", BR-06). Same permissions as the SKU's logistics.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/purchasing")
@Tag(name = "SKU purchasing", description = "Purchasing information of a SKU's inventory item")
class SkuPurchasingController {

    private final SkuPurchasingService service;
    private final SkuUnitWebMapper mapper;

    SkuPurchasingController(SkuPurchasingService service, SkuUnitWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<SkuPurchasingResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variantId, service.purchasing(productId, variantId)));
    }

    @PutMapping
    @Operation(summary = "Replace a SKU's purchasing information; quote the version the last read returned",
            description = "A null field clears it. A changed defaultSupplierId must exist (404 SUPPLIER_NOT_FOUND) "
                    + "and be ACTIVE (409 SUPPLIER_INACTIVE). Stale version: 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SkuPurchasingResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                     @Valid @RequestBody SkuPurchasingRequest request) {
        return ApiResponse.ok(mapper.toResponse(variantId,
                service.describe(productId, variantId, request.version(), mapper.toPurchasing(request))));
    }
}
