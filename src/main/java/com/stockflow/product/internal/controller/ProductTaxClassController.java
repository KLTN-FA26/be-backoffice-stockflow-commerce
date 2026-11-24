package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.ChangeTaxClassRequest;
import com.stockflow.product.internal.controller.dto.ProductTaxClassResponse;
import com.stockflow.product.internal.domain.ProductTaxClass;
import com.stockflow.product.internal.service.ProductTaxClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * WF-05 D-8: a product's VAT group, set or changed in any status ({@link ProductTaxClassService}). The accountant
 * reaches it from an invoice draft that names a product without one, by the SKUs on its lines; the Sales Manager from
 * the catalogue.
 *
 * <p>The read takes the same permission as the change: it exists for the dialog that changes the class, and a
 * product's master data has its own read ({@code product-products:READ}), which the accountant does not hold.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product master data")
@PermissionResource(
        code = ProductResources.TAX_CLASS,
        group = "Product",
        label = "Product tax class (VAT group)",
        route = "/admin/products",
        apiPath = "/api/v1/products",
        actions = {Action.UPDATE})
class ProductTaxClassController {

    private final ProductTaxClassService taxClasses;

    ProductTaxClassController(ProductTaxClassService taxClasses) {
        this.taxClasses = taxClasses;
    }

    @GetMapping("/tax-classes")
    @Operation(summary = "The product of each SKU with its VAT group and version - what the change quotes back")
    @RequiresPermission(resource = ProductResources.TAX_CLASS, action = Action.UPDATE)
    public ApiResponse<List<ProductTaxClassResponse>> ofSkus(@RequestParam(name = "sku") List<String> skus) {
        return ApiResponse.ok(taxClasses.ofSkus(skus).stream().map(ProductTaxClassController::toResponse).toList());
    }

    @PutMapping("/{productId}/tax-class")
    @Operation(summary = "Set or change a product's VAT group in any status, with a reason; affects quotes and invoice "
            + "drafts drawn up from now on only")
    @RequiresPermission(resource = ProductResources.TAX_CLASS, action = Action.UPDATE)
    public ApiResponse<ProductTaxClassResponse> change(@PathVariable UUID productId,
                                                       @Valid @RequestBody ChangeTaxClassRequest request) {
        return ApiResponse.ok(toResponse(taxClasses.change(productId, request.taxClass(), request.reason(),
                request.version())));
    }

    private static ProductTaxClassResponse toResponse(ProductTaxClass found) {
        return new ProductTaxClassResponse(found.sku(), found.productId(), found.productCode(), found.productName(),
                found.status(), found.taxClass(), found.version());
    }
}
