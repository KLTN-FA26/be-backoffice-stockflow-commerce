package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.StandardPriceRequest;
import com.stockflow.catalog.internal.controller.dto.StandardPriceResponse;
import com.stockflow.catalog.internal.service.StandardPriceService;
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

/**
 * Back office: the standard price of each variant of the catalogue, by carton break (kltn-docs 13 BR-04 as changed on
 * 2026-11-26). The Sales Manager edits; Sales reads - a quote line of a SKU drawn on the variant starts from it when the
 * customer has no price of its own, and a quote priced more than the threshold below it waits for the Sales Manager
 * (14 BR-10). Only staff hold this resource, and there is no portal path to it: a customer sees a price only on a quote,
 * order or contract.
 */
@RestController
@RequestMapping("/api/v1/catalog/standard-prices")
@PermissionResource(code = StandardPriceController.RESOURCE, group = "Catalog", label = "Standard prices",
        route = "/catalog/standard-prices", apiPath = "/api/v1/catalog/standard-prices",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class StandardPriceController {

    static final String RESOURCE = "catalog-standard-prices";

    private final StandardPriceService prices;

    StandardPriceController(StandardPriceService prices) {
        this.prices = prices;
    }

    @GetMapping
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<List<StandardPriceResponse>> list(
            @RequestParam(name = "sku", required = false) List<String> skus) {
        return ApiResponse.ok(prices.list(skus == null ? List.of() : skus).stream()
                .map(StandardPriceController::toResponse).toList());
    }

    @GetMapping("/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<StandardPriceResponse> get(@PathVariable String sku) {
        return ApiResponse.ok(toResponse(prices.get(sku)));
    }

    @PostMapping("/{sku}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = RESOURCE, action = Action.CREATE)
    public ApiResponse<StandardPriceResponse> create(@PathVariable String sku,
                                                     @Valid @RequestBody StandardPriceRequest request) {
        return ApiResponse.ok(toResponse(prices.create(sku, tiers(request), request.note())));
    }

    @PutMapping("/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.UPDATE)
    public ApiResponse<StandardPriceResponse> replace(@PathVariable String sku,
                                                      @Valid @RequestBody StandardPriceRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version is required to replace a price");
        }
        return ApiResponse.ok(toResponse(prices.replace(sku, tiers(request), request.note(), request.version())));
    }

    @DeleteMapping("/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable String sku, @RequestParam long version) {
        prices.delete(sku, version);
        return ApiResponse.ok(null);
    }

    private static List<StandardPriceService.TierInput> tiers(StandardPriceRequest request) {
        return request.tiers().stream()
                .map(t -> new StandardPriceService.TierInput(t.minQuantity(), t.unitPrice())).toList();
    }

    private static StandardPriceResponse toResponse(StandardPriceService.PriceView price) {
        return new StandardPriceResponse(price.sku(), price.productName(), price.variantName(), price.moq(),
                price.tiers().stream().map(t -> new StandardPriceResponse.Tier(t.minQuantity(), t.unitPrice()))
                        .toList(), price.note(), price.version());
    }
}
