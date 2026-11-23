package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CustomerSkuResponse;
import com.stockflow.product.internal.service.CustomerSkuListing;
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

    private final CustomerSkuListing listing;

    CustomerSkuController(CustomerSkuListing listing) {
        this.listing = listing;
    }

    @GetMapping
    @Operation(summary = "A customer's own SKUs, of one product when productId is given, each with the pieces per carton "
            + "and the VAT rate a quote line of it copies today")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<List<CustomerSkuResponse>> list(@RequestParam UUID customerId,
                                                       @RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(listing.ownedBy(customerId, productId).stream()
                .map(listed -> new CustomerSkuResponse(listed.sku().variantId(), listed.sku().productId(),
                        listed.sku().sku(), listed.sku().name(), listed.sku().ownerCustomerId(),
                        listed.sku().designSnapshotId(), listed.sku().sourceSku(), listed.sku().status(),
                        listed.unitsPerCarton(), listed.taxClass(), listed.taxRate()))
                .toList());
    }
}
