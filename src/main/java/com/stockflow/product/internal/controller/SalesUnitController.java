package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.SalesUnitResponse;
import com.stockflow.product.internal.service.SkuUnitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The unit a variant is sold in (docs 01 §3 "Đơn vị bán", BR-11): the carton of its carton specification, which the
 * system assigns - read-only, there is nothing to list or choose. It carries no price (kltn-docs 13 BR-04: no base
 * price; a price per piece is agreed on the quote).
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/sales-units")
@Tag(name = "Variant sales units", description = "The unit a variant is sold in: its carton")
class SalesUnitController {

    private final SkuUnitService service;
    private final SkuUnitWebMapper mapper;

    SalesUnitController(SkuUnitService service, SkuUnitWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "The variant's sales unit: its carton; empty while the SKU has no carton specification")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<SalesUnitResponse>> list(@PathVariable UUID productId,
                                                             @PathVariable UUID variantId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.salesUnits(productId, variantId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }
}
