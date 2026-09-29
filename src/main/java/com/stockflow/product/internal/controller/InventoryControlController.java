package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.InventoryControlResponse;
import com.stockflow.product.internal.controller.dto.UpdateInventoryControlRequest;
import com.stockflow.product.internal.service.SkuInventoryControlService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products/{productId}/skus/{skuId}/inventory-control")
class InventoryControlController {
    private final SkuInventoryControlService service;
    private final InventoryControlWebMapper mapper;
    InventoryControlController(SkuInventoryControlService service, InventoryControlWebMapper mapper) {
        this.service = service; this.mapper = mapper;
    }
    @GetMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<InventoryControlResponse> get(@PathVariable UUID productId, @PathVariable UUID skuId) {
        return ApiResponse.ok(mapper.toResponse(service.get(productId, skuId)));
    }
    @PutMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<InventoryControlResponse> update(@PathVariable UUID productId, @PathVariable UUID skuId,
            @Valid @RequestBody UpdateInventoryControlRequest request) {
        return ApiResponse.ok(mapper.toResponse(service.update(productId, skuId, request.version(), mapper.toPolicy(request))));
    }
}
