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
 * fields of the product; they are per SKU now, held on its inventory item. {@code skuId} is the
 * variant's id.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/skus/{skuId}/logistics")
@Tag(name = "SKU logistics", description = "Physical and handling data of a SKU")
class SkuLogisticsController {

    private final SkuInventoryControlService service;
    private final ProductAdminWebMapper mapper;

    SkuLogisticsController(SkuInventoryControlService service, ProductAdminWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<SkuLogisticsResponse> get(@PathVariable UUID productId, @PathVariable UUID skuId) {
        return ApiResponse.ok(mapper.toResponse(skuId, service.logistics(productId, skuId)));
    }

    @PutMapping
    @Operation(summary = "Replace a SKU's logistics; quote the version the last read returned")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<SkuLogisticsResponse> update(@PathVariable UUID productId, @PathVariable UUID skuId,
                                                    @Valid @RequestBody SkuLogisticsRequest request) {
        return ApiResponse.ok(mapper.toResponse(skuId,
                service.describe(productId, skuId, request.version(), mapper.toLogistics(request))));
    }
}
