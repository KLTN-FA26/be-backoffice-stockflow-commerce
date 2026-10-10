package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.VariantRequest;
import com.stockflow.product.internal.controller.dto.VariantResponse;
import com.stockflow.product.internal.service.ProductVariantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * The variants of a product (WBS 3.1.2). A variant's id is the {@code skuId} of the SKU routes
 * ({@code /skus/{skuId}/logistics}, {@code /skus/{skuId}/inventory-control}).
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants")
@Tag(name = "Product variants", description = "Sellable SKUs of a product")
class ProductVariantController {

    private final ProductVariantService variants;
    private final ProductAdminWebMapper mapper;

    ProductVariantController(ProductVariantService variants, ProductAdminWebMapper mapper) {
        this.variants = variants;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List a product's variants, by position")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<VariantResponse>> list(@PathVariable UUID productId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(variants.list(productId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/{variantId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<VariantResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.get(productId, variantId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a DRAFT variant; it creates the SKU's inventory item")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<VariantResponse> add(@PathVariable UUID productId, @Valid @RequestBody VariantRequest request) {
        return ApiResponse.ok(mapper.toResponse(variants.add(productId, command(request))));
    }

    @PutMapping("/{variantId}")
    @Operation(summary = "Rename or reorder a variant; the SKU changes only while it is DRAFT")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<VariantResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                               @Valid @RequestBody VariantRequest request) {
        return ApiResponse.ok(mapper.toResponse(variants.update(productId, variantId, command(request))));
    }

    @PostMapping("/{variantId}/activation")
    @Operation(summary = "Put a variant on sale (product approved or published)")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<VariantResponse> activate(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.activate(productId, variantId)));
    }

    @PostMapping("/{variantId}/blocking")
    @Operation(summary = "Take a variant off sale for now")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<VariantResponse> block(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.block(productId, variantId)));
    }

    @PostMapping("/{variantId}/obsoletion")
    @Operation(summary = "Retire a variant for good (not the default one)")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<VariantResponse> obsolete(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.obsolete(productId, variantId)));
    }

    private static ProductVariantService.NewVariant command(VariantRequest r) {
        return new ProductVariantService.NewVariant(r.sku(), r.name(), r.attributeSignature(), r.position());
    }
}
