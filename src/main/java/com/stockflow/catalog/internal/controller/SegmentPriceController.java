package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.SegmentPriceRequest;
import com.stockflow.catalog.internal.controller.dto.SegmentPriceResponse;
import com.stockflow.catalog.internal.service.SegmentPriceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Back office: customer-group prices by SKU (user decision P7). E-commerce Admin and System Admin edit; Sales
 * and the Order Coordinator read - Sales negotiates on the quote, never on the group's price. Grants:
 * V20261014000530.
 */
@RestController
@RequestMapping("/api/v1/catalog/segment-prices")
@PermissionResource(code = SegmentPriceController.RESOURCE, group = "Catalog", label = "Customer group prices",
        route = "/customers", apiPath = "/api/v1/catalog/segment-prices",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class SegmentPriceController {

    static final String RESOURCE = "catalog-segment-prices";

    private final SegmentPriceService prices;

    SegmentPriceController(SegmentPriceService prices) {
        this.prices = prices;
    }

    @GetMapping
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<List<SegmentPriceResponse>> list(@RequestParam UUID segmentId,
                                                        @RequestParam(name = "sku", required = false) List<String> skus) {
        return ApiResponse.ok(prices.list(segmentId, skus == null ? List.of() : skus).stream()
                .map(SegmentPriceController::toResponse).toList());
    }

    @GetMapping("/{segmentId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<SegmentPriceResponse> get(@PathVariable UUID segmentId, @PathVariable String sku) {
        return ApiResponse.ok(toResponse(prices.get(segmentId, sku)));
    }

    @PostMapping("/{segmentId}/{sku}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = RESOURCE, action = Action.CREATE)
    public ApiResponse<SegmentPriceResponse> create(@PathVariable UUID segmentId, @PathVariable String sku,
                                                    @Valid @RequestBody SegmentPriceRequest request) {
        return ApiResponse.ok(toResponse(prices.create(segmentId, sku, request.price())));
    }

    @PutMapping("/{segmentId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.UPDATE)
    public ApiResponse<SegmentPriceResponse> replace(@PathVariable UUID segmentId, @PathVariable String sku,
                                                     @Valid @RequestBody SegmentPriceRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version is required to replace a price");
        }
        return ApiResponse.ok(toResponse(prices.replace(segmentId, sku, request.price(), request.version())));
    }

    @DeleteMapping("/{segmentId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable UUID segmentId, @PathVariable String sku, @RequestParam long version) {
        prices.delete(segmentId, sku, version);
        return ApiResponse.ok(null);
    }

    private static SegmentPriceResponse toResponse(SegmentPriceService.PriceView price) {
        return new SegmentPriceResponse(price.segmentId(), price.sku(), price.price(), "VND", price.version());
    }
}
