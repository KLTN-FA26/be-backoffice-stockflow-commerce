package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CustomerSkuResponse;
import com.stockflow.product.internal.service.CustomerSkuLifecycleService;
import com.stockflow.product.internal.service.CustomerSkuListing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * A customer's own SKUs for the back office (kltn-docs 12 §4.6 step 18): what a Sales Staff member looks up to put a
 * customer's printed goods on a quote, and the Production Coordinator to draw a production order up. Read with
 * {@code product-variants:READ}, as every other variant is; the SKUs are issued only by a design's approval, never by
 * hand. Handlers are public so the permission proxy advises them.
 *
 * <p>Deliberately not narrowed to "the Sales owning the customer" (SCRUM-716): 12 BR-03 lets every Sales Staff member,
 * the Sales Manager and the coordinator look any customer's own SKUs up, and the system has no Sales owning a customer
 * (kltn-docs 18). The customer never reaches this endpoint (no {@code product-variants:READ}); on the portal they see
 * only their own SKUs (01 BR-20).</p>
 *
 * <p>Blocking, unblocking and retiring are here too ({@code /{id}/blocking}, {@code /activation},
 * {@code /obsoletion}): a customer's SKU is its own table since V20270105000100, no longer a variant reached through
 * its product, and its id is unknown to {@code ProductVariantController}. The permissions are the ones the variant
 * moves take - UPDATE to take it off sale, APPROVE to put it back on sale or retire it.</p>
 */
@RestController
@RequestMapping("/api/v1/customer-skus")
@Tag(name = "Customer SKUs", description = "The SKUs issued to a customer for their approved designs")
class CustomerSkuController {

    private final CustomerSkuListing listing;
    private final CustomerSkuLifecycleService lifecycle;

    CustomerSkuController(CustomerSkuListing listing, CustomerSkuLifecycleService lifecycle) {
        this.listing = listing;
        this.lifecycle = lifecycle;
    }

    /**
     * Two questions, told apart by {@code customerId}. With it: a customer's own SKUs, narrowed to a product and a
     * catalogue variant when those are given - a list, as the quote editor has always read it. Without it: every
     * customer's SKU drawn on {@code productId} or on the variant {@code variantId}, whoever owns it - what the product
     * page shows before a blank is blocked or the product discontinued - a page, since a common blank may carry many.
     * One of the three is required, so the whole table is never returned.
     */
    @GetMapping
    @Operation(summary = "Customer SKUs: a customer's own (customerId, a list), or every customer's drawn on a product "
            + "or a catalogue variant (productId / variantId without customerId, a page)",
            description = "Each with the pieces per carton, the VAT rate a quote line of it copies today and its owner's "
                    + "name and code. With customerId the body is a list, narrowed by productId and variantId when "
                    + "given; without it, a PageResponse (page, size) of every customer's SKU on the product or "
                    + "variant. 400 VALIDATION_FAILED when none of the three is given.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<?> list(@RequestParam(required = false) UUID customerId,
                               @RequestParam(required = false) UUID productId,
                               @RequestParam(required = false) UUID variantId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        if (customerId == null && productId == null && variantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Name a customer, a product or a catalogue variant to list customer SKUs of",
                    List.of(FieldError.of("customerId", "customerId, productId or variantId is required")));
        }
        if (customerId != null) {
            return ApiResponse.ok(listing.ownedBy(customerId, productId, variantId).stream()
                    .map(CustomerSkuController::response).toList());
        }
        return ApiResponse.ok(listing.drawnOn(productId, variantId, page, size).map(CustomerSkuController::response));
    }

    @PostMapping("/{id}/activation")
    @Operation(summary = "Put a blocked customer SKU on sale again (its product on sale, its variant active)",
            description = "404 CUSTOMER_SKU_NOT_FOUND; 409 INVALID_CUSTOMER_SKU_TRANSITION when it is not BLOCKED, "
                    + "or its product is not approved or published, or the variant it was drawn on is not ACTIVE.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.APPROVE)
    public ApiResponse<CustomerSkuResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(response(listing.one(lifecycle.activate(id))));
    }

    @PostMapping("/{id}/blocking")
    @Operation(summary = "Take a customer SKU off sale for now",
            description = "404 CUSTOMER_SKU_NOT_FOUND; 409 INVALID_CUSTOMER_SKU_TRANSITION unless it is ACTIVE.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.UPDATE)
    public ApiResponse<CustomerSkuResponse> block(@PathVariable UUID id) {
        return ApiResponse.ok(response(listing.one(lifecycle.block(id))));
    }

    @PostMapping("/{id}/obsoletion")
    @Operation(summary = "Retire a customer SKU for good",
            description = "404 CUSTOMER_SKU_NOT_FOUND; 409 INVALID_CUSTOMER_SKU_TRANSITION when it is OBSOLETE already.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.APPROVE)
    public ApiResponse<CustomerSkuResponse> obsolete(@PathVariable UUID id) {
        return ApiResponse.ok(response(listing.one(lifecycle.obsolete(id))));
    }

    private static CustomerSkuResponse response(CustomerSkuListing.Listed listed) {
        return new CustomerSkuResponse(listed.sku().id(), listed.sku().productId(), listed.sku().sku(),
                listed.sku().name(), listed.sku().ownerCustomerId(), listed.sku().designSnapshotId(),
                listed.sku().sourceVariantId(), listed.sku().sourceSku(), listed.sku().status(),
                listed.unitsPerCarton(), listed.taxClass(), listed.taxRate(), listed.ownerName(),
                listed.ownerCode());
    }
}
