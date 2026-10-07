package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Existing publication URLs are preserved; orchestration belongs to catalog, which owns prices. */
@RestController
@RequestMapping("/api/v1/products/{productId}")
class CatalogManagementController {
    private final CatalogCommerceService service;
    private final CatalogWebMapper mapper;

    CatalogManagementController(CatalogCommerceService service, CatalogWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/ecommerce")
    @RequiresPermission(resource = "product-products", action = Action.READ)
    public ApiResponse<ListingResponse> get(@PathVariable UUID productId) {
        return ApiResponse.ok(mapper.toResponse(service.get(productId)));
    }

    @PutMapping("/ecommerce")
    @RequiresPermission(resource = "product-products", action = Action.UPDATE)
    public ApiResponse<ListingResponse> edit(
            @PathVariable UUID productId, @Valid @RequestBody EditListingRequest r) {
        return ApiResponse.ok(
                mapper.toResponse(
                        service.edit(
                                productId,
                                r.revision(),
                                r.slug(),
                                r.seoTitle(),
                                r.seoDescription())));
    }

    @PutMapping("/skus/{sku}/base-price")
    @RequiresPermission(resource = "product-products", action = Action.UPDATE)
    public ApiResponse<ListingResponse> price(
            @PathVariable UUID productId,
            @PathVariable String sku,
            @Valid @RequestBody SetBasePriceRequest r,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(
                mapper.toResponse(
                        service.price(
                                productId,
                                sku,
                                r.revision(),
                                mapper.toPrice(r),
                                user == null || user.userId() == null
                                        ? "system"
                                        : user.userId().toString())));
    }

    @GetMapping("/skus/{sku}/base-price")
    @RequiresPermission(resource = "product-products", action = Action.READ)
    public ApiResponse<SkuPriceResponse> price(
            @PathVariable UUID productId, @PathVariable String sku) {
        return ApiResponse.ok(mapper.toResponse(service.price(productId, sku)));
    }

    @PostMapping("/publication")
    @RequiresPermission(resource = "product-products", action = Action.APPROVE)
    public ApiResponse<Void> publish(@PathVariable UUID productId) {
        service.publish(productId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/unpublication")
    @RequiresPermission(resource = "product-products", action = Action.APPROVE)
    public ApiResponse<Void> unpublish(@PathVariable UUID productId) {
        service.unpublish(productId);
        return ApiResponse.ok(null);
    }
}
