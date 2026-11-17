package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * A product's storefront listing in the back office: its SEO content and its publication. Existing publication URLs are
 * preserved. There is no base price to set any more (kltn-docs 13 BR-04, team plan 5-3): a variant has no price, and a
 * customer's own SKU is priced by the customer's internal price list ({@code /api/v1/catalog/customer-prices}).
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
