package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.SkuLogisticsRequest;
import com.stockflow.product.internal.controller.dto.SkuLogisticsResponse;
import com.stockflow.product.internal.service.SkuInventoryControlService;
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
 * Weight, dimensions, package, storage class and QC flag of one SKU (SCRUM-74/75/76). They used to be
 * fields of the product; they are per SKU now, held on its inventory item.
 *
 * <p>The canonical route names what the id is: {@code /variants/{variantId}/logistics}. The older
 * {@code /skus/{skuId}/logistics} takes the same variant id, which read like a SKU code next to
 * {@code /skus/{sku}/base-price} (that one does take a code); it still works, with the same permission, and is
 * marked deprecated in the API description.</p>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
@Tag(name = "SKU logistics", description = "Physical and handling data of a SKU")
class SkuLogisticsController {

    private final SkuInventoryControlService service;
    private final ProductAdminWebMapper mapper;

    SkuLogisticsController(SkuInventoryControlService service, ProductAdminWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/variants/{variantId}/logistics")
    @Operation(summary = "A variant's logistics; an obsolete variant is still readable")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<SkuLogisticsResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variantId, service.logistics(productId, variantId)));
    }

    @PutMapping("/variants/{variantId}/logistics")
    @Operation(summary = "Replace a variant's logistics; quote the version the last read returned",
            description = "An obsolete variant is refused with 409 VARIANT_OBSOLETE.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SkuLogisticsResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                    @Valid @RequestBody SkuLogisticsRequest request) {
        return ApiResponse.ok(mapper.toResponse(variantId,
                service.describe(productId, variantId, request.version(), mapper.toLogistics(request))));
    }

    @GetMapping("/skus/{skuId}/logistics")
    @Operation(summary = "Deprecated: use /variants/{variantId}/logistics (skuId is the variant id)", deprecated = true)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<SkuLogisticsResponse> getBySkuRoute(@PathVariable UUID productId, @PathVariable UUID skuId) {
        return get(productId, skuId);
    }

    @PutMapping("/skus/{skuId}/logistics")
    @Operation(summary = "Deprecated: use /variants/{variantId}/logistics (skuId is the variant id)", deprecated = true)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SkuLogisticsResponse> updateBySkuRoute(@PathVariable UUID productId, @PathVariable UUID skuId,
                                                              @Valid @RequestBody SkuLogisticsRequest request) {
        return update(productId, skuId, request);
    }
}
