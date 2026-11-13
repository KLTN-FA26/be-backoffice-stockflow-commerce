package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A customer's own SKUs for the back office (kltn-docs 12 §4.6 step 18): what a Sales Staff member looks up to put a
 * customer's printed goods on a quote. Read with {@code product-variants:READ}, as every other variant is; the SKUs are
 * issued only by a design's approval, never by hand. Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1/customer-skus")
@Tag(name = "Customer SKUs", description = "The SKUs issued to a customer for their approved designs")
class CustomerSkuController {

    private final CustomerSkus customerSkus;

    CustomerSkuController(CustomerSkus customerSkus) {
        this.customerSkus = customerSkus;
    }

    @GetMapping
    @Operation(summary = "A customer's own SKUs, of one product when productId is given")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<List<CustomerSku>> list(@RequestParam UUID customerId,
                                               @RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(customerSkus.ownedBy(customerId, productId));
    }
}
