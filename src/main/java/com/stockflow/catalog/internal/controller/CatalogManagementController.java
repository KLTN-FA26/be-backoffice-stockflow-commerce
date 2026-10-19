package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Existing publication URLs are preserved; orchestration belongs to catalog, which owns prices. A base price is set
 * per variant: {@code /variants/{variantId}/base-price} is the canonical route, like a variant's logistics and stock
 * policy; {@code /skus/{sku}/base-price} (a SKU code, normalised) still works and is marked deprecated.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
class CatalogManagementController {
    private final CatalogCommerceService service;
    private final CatalogCommerceWebMapper mapper;

    CatalogManagementController(CatalogCommerceService service, CatalogCommerceWebMapper mapper) {
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

    @PutMapping("/variants/{variantId}/base-price")
    @Operation(summary = "Set the base price of a variant on sale; quote the listing revision")
    @RequiresPermission(resource = "product-products", action = Action.UPDATE)
    public ApiResponse<ListingResponse> priceOfVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody SetBasePriceRequest r,
            @AuthenticatedUser CurrentUser user) {
        return price(productId, service.skuOfVariant(productId, variantId), r, user);
    }

    @GetMapping("/variants/{variantId}/base-price")
    @Operation(summary = "The base and effective price of a variant on sale")
    @RequiresPermission(resource = "product-products", action = Action.READ)
    public ApiResponse<SkuPriceResponse> priceOfVariant(
            @PathVariable UUID productId, @PathVariable UUID variantId) {
        return price(productId, service.skuOfVariant(productId, variantId));
    }

    /** Takes the SKU code, in any case ({@code cup-12oz} is {@code CUP-12OZ}). */
    @PutMapping("/skus/{sku}/base-price")
    @Operation(summary = "Deprecated: use /variants/{variantId}/base-price (sku is the SKU code, any case)",
            deprecated = true)
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

    /** Takes the SKU code, in any case ({@code cup-12oz} is {@code CUP-12OZ}). */
    @GetMapping("/skus/{sku}/base-price")
    @Operation(summary = "Deprecated: use /variants/{variantId}/base-price (sku is the SKU code, any case)",
            deprecated = true)
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
