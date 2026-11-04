package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.internal.controller.dto.DiscontinueProductRequest;
import com.stockflow.product.internal.controller.dto.ProductDiscontinuationResponse;
import com.stockflow.product.internal.controller.dto.ProductResponse;
import com.stockflow.product.internal.controller.dto.ResumeProductRequest;
import com.stockflow.product.internal.controller.dto.SuspendProductRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A product's end of life (SCRUM-461): suspend (→ {@code INACTIVE}), resume (→ {@code APPROVED}) and discontinue
 * (→ {@code DISCONTINUED}, terminal). Same URLs as before, under {@code /api/v1/products}.
 *
 * <p><b>Who.</b> kltn-docs 01 §5.1: {@code Active → Inactive}, {@code Inactive → Active} and {@code Discontinued} are
 * the System Admin's. These handlers used {@code product-products:APPROVE}, which the E-commerce Admin holds to
 * approve and publish; they now have a resource of their own, {@code product-lifecycle:APPROVE}, granted to
 * SYSTEM_ADMIN only (V20261019000700). APPROVE because it is the sensitive, sign-off class of action: an
 * administrator ticks it explicitly, never through "select all". Approving, rejecting, publishing and
 * unpublishing keep {@code product-products:APPROVE}.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product master data")
@PermissionResource(
        code = ProductResources.LIFECYCLE,
        group = "Product",
        label = "Product lifecycle (suspend, resume, discontinue)",
        route = "/admin/products",
        apiPath = "/api/v1/products",
        actions = {Action.APPROVE})
class ProductLifecycleController {

    private final ProductService productService;
    private final ProductWebMapper mapper;

    ProductLifecycleController(ProductService productService, ProductWebMapper mapper) {
        this.productService = productService;
        this.mapper = mapper;
    }

    /**
     * Terminal (WBS 3.1.8.1). BR-PRD-005 is a warning (kltn-docs 01 step 12): the product is discontinued
     * and the response lists the purchase order lines and order lines that still refer to it. The body
     * carries the reason (mandatory) and the version the screen read.
     */
    @PostMapping("/{productId}/discontinuation")
    @Operation(summary = "Discontinue an approved, published or suspended product, with a reason; "
            + "open purchase orders and orders are reported as warnings, never refused")
    @RequiresPermission(resource = ProductResources.LIFECYCLE, action = Action.APPROVE)
    public ApiResponse<ProductDiscontinuationResponse> discontinue(@PathVariable UUID productId,
                                                                   @Valid @RequestBody DiscontinueProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                productService.discontinue(productId, request.reason(), request.version())));
    }

    @PostMapping("/{productId}/suspension")
    @Operation(summary = "Suspend an approved or published product (INACTIVE): hidden, no new purchase order lines")
    @RequiresPermission(resource = ProductResources.LIFECYCLE, action = Action.APPROVE)
    public ApiResponse<ProductResponse> suspend(@PathVariable UUID productId,
                                                @Valid @RequestBody SuspendProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                productService.suspend(productId, request.reason(), request.version())));
    }

    @PostMapping("/{productId}/resumption")
    @Operation(summary = "Resume a suspended product: back to APPROVED; publish it again to show it")
    @RequiresPermission(resource = ProductResources.LIFECYCLE, action = Action.APPROVE)
    public ApiResponse<ProductResponse> resume(@PathVariable UUID productId,
                                               @Valid @RequestBody ResumeProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(productService.resume(productId, request.version())));
    }
}
