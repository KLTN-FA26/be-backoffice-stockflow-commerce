package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.CustomerPriceRequest;
import com.stockflow.catalog.internal.controller.dto.CustomerPriceResponse;
import com.stockflow.catalog.internal.service.CustomerPriceService;
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
 * Back office: customers' own prices by SKU and quantity tier (SCRUM-428) - the internal price, the one source of a
 * price (kltn-docs 13 BR-04, BR-10). Sales Manager edits (13 §2); Sales reads - to start
 * a quote from the customer's price (14 step 2). Only staff hold this resource, and there is no portal path to a price
 * at all: a customer sees one only on a quote, order or contract.
 */
@RestController
@RequestMapping("/api/v1/catalog/customer-prices")
@PermissionResource(code = CustomerPriceController.RESOURCE, group = "Catalog", label = "Customer prices",
        route = "/customers", apiPath = "/api/v1/catalog/customer-prices",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class CustomerPriceController {

    static final String RESOURCE = "catalog-customer-prices";

    private final CustomerPriceService prices;

    CustomerPriceController(CustomerPriceService prices) {
        this.prices = prices;
    }

    @GetMapping
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<List<CustomerPriceResponse>> list(@RequestParam UUID customerId,
                                                         @RequestParam(name = "sku", required = false) List<String> skus) {
        return ApiResponse.ok(prices.list(customerId, skus == null ? List.of() : skus).stream()
                .map(CustomerPriceController::toResponse).toList());
    }

    @GetMapping("/{customerId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.READ)
    public ApiResponse<CustomerPriceResponse> get(@PathVariable UUID customerId, @PathVariable String sku) {
        return ApiResponse.ok(toResponse(prices.get(customerId, sku)));
    }

    @PostMapping("/{customerId}/{sku}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = RESOURCE, action = Action.CREATE)
    public ApiResponse<CustomerPriceResponse> create(@PathVariable UUID customerId, @PathVariable String sku,
                                                     @Valid @RequestBody CustomerPriceRequest request) {
        return ApiResponse.ok(toResponse(prices.create(customerId, sku, tiers(request), request.note())));
    }

    @PutMapping("/{customerId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.UPDATE)
    public ApiResponse<CustomerPriceResponse> replace(@PathVariable UUID customerId, @PathVariable String sku,
                                                      @Valid @RequestBody CustomerPriceRequest request) {
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "version is required to replace a price");
        }
        return ApiResponse.ok(toResponse(prices.replace(customerId, sku, tiers(request), request.note(),
                request.version())));
    }

    @DeleteMapping("/{customerId}/{sku}")
    @RequiresPermission(resource = RESOURCE, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable UUID customerId, @PathVariable String sku,
                                    @RequestParam long version) {
        prices.delete(customerId, sku, version);
        return ApiResponse.ok(null);
    }

    private static List<CustomerPriceService.TierInput> tiers(CustomerPriceRequest request) {
        return request.tiers().stream()
                .map(t -> new CustomerPriceService.TierInput(t.minQuantity(), t.unitPrice())).toList();
    }

    private static CustomerPriceResponse toResponse(CustomerPriceService.PriceView price) {
        return new CustomerPriceResponse(price.customerId(), price.sku(), price.measure(), price.moq(),
                price.tiers().stream().map(t -> new CustomerPriceResponse.Tier(t.minQuantity(), t.unitPrice())).toList(),
                price.note(), price.version());
    }
}
