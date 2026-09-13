package com.stockflow.product.internal.controller;

import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.internal.controller.dto.CreateProductRequest;
import com.stockflow.product.internal.controller.dto.ProductResponse;
import com.stockflow.product.internal.controller.dto.RejectProductRequest;
import com.stockflow.product.internal.controller.dto.UpdateProductRequest;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * HTTP entry point for the product master (SCRUM-56/WBS 3.1.1.2) and its approval workflow
 * (SCRUM-57/WBS 3.1.1.3).
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} is applied by a Spring AOP
 * proxy, which advises a non-public method only when the generated proxy happens to land in the
 * same package and classloader — when that does not hold, the guard is skipped silently.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product master data")
@PermissionResource(
        code = ProductResources.PRODUCTS,
        group = "Product",
        label = "Products",
        route = "/admin/products",
        apiPath = "/api/v1/products",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class ProductController {

    private final ProductService productService;
    private final ProductWebMapper mapper;

    ProductController(ProductService productService, ProductWebMapper mapper) {
        this.productService = productService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List products, paginated and filterable")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<ProductResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) List<ProductStatus> status,
            @RequestParam(required = false) String sort) {
        var query = new ListProductsQuery(
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, search, status, sort);
        return ApiResponse.ok(productService.list(query).map(mapper::toResponse));
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Look up one product")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<ProductResponse> findOne(@PathVariable UUID productId) {
        return productService.findById(productId)
                .map(mapper::toResponse)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PRODUCT_NOT_FOUND, "No product with id " + productId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a product master row")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.CREATE)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(productService.create(mapper.toCommand(request))));
    }

    @PutMapping("/{productId}")
    @Operation(summary = "Update a product master row")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductResponse> update(@PathVariable UUID productId,
                                               @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                productService.update(mapper.toCommand(productId, request))));
    }

    @PostMapping("/{productId}/submission")
    @Operation(summary = "Submit a draft product for approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductResponse> submit(@PathVariable UUID productId,
                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(productService.submit(productId, user.userId())));
    }

    @PostMapping("/{productId}/approval")
    @Operation(summary = "Approve a product pending approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<ProductResponse> approve(@PathVariable UUID productId,
                                                @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(productService.approve(productId, user.userId())));
    }

    @PostMapping("/{productId}/rejection")
    @Operation(summary = "Reject a product pending approval, sending it back to draft")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<ProductResponse> reject(@PathVariable UUID productId,
                                               @AuthenticatedUser CurrentUser user,
                                               @Valid @RequestBody RejectProductRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                productService.reject(productId, user.userId(), request.reason())));
    }
}
