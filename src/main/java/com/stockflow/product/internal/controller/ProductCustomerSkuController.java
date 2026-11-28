package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CustomerSkuResponse;
import com.stockflow.product.internal.service.CustomerSkuListing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Every customer's SKUs of one product, for the product page (the "customer SKUs" tab): whose designs are printed on
 * the product, or on one of its catalogue variants, before a blank is blocked - which stops those SKUs from being
 * ordered ({@code SOURCE_VARIANT_NOT_ACTIVE}) - or the product is discontinued, which retires them. Paged, because a
 * common blank carries many customers' SKUs; each row names its owner, so the page needs no customer lookup.
 *
 * <p>Its own endpoint rather than another shape of {@code GET /customer-skus}, which answers one customer's list for
 * the quote editor: one URL, one body. Staff only ({@code product-variants:READ}, as every variant read): a customer
 * never holds it, so no customer sees another's SKUs here (13 BR-02). Handlers are public so the permission proxy
 * advises them.</p>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/customer-skus")
@Tag(name = "Customer SKUs", description = "The SKUs issued to a customer for their approved designs")
class ProductCustomerSkuController {

    private final CustomerSkuListing listing;

    ProductCustomerSkuController(CustomerSkuListing listing) {
        this.listing = listing;
    }

    @GetMapping
    @Operation(summary = "Every customer's SKUs of a product, or of one of its catalogue variants, a page at a time",
            description = "Ordered by code; each with its owner's name and code, the pieces per carton and the VAT rate "
                    + "a quote line of it copies today. 404 PRODUCT_NOT_FOUND for an unknown product; 404 "
                    + "VARIANT_NOT_FOUND when variantId is not a variant of this product.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<PageResponse<CustomerSkuResponse>> list(@PathVariable UUID productId,
                                                               @RequestParam(required = false) UUID variantId,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(listing.drawnOn(productId, variantId, page, size)
                .map(CustomerSkuController::response));
    }
}
