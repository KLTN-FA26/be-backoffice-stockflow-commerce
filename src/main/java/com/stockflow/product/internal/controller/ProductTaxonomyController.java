package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.BrandResponse;
import com.stockflow.product.internal.controller.dto.CategoryResponse;
import com.stockflow.product.internal.controller.dto.CreateBrandRequest;
import com.stockflow.product.internal.controller.dto.CreateCategoryRequest;
import com.stockflow.product.internal.controller.dto.UpdateBrandRequest;
import com.stockflow.product.internal.controller.dto.UpdateCategoryRequest;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;
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

import java.util.Objects;
import java.util.UUID;

/**
 * Brands and categories — the reference data a product points at. Guarded by the product resource:
 * whoever maintains products maintains what they are filed under, so no new grant is needed.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Brands and categories", description = "Product reference data")
class ProductTaxonomyController {

    private final ProductTaxonomyService taxonomy;
    private final ProductAdminWebMapper mapper;

    ProductTaxonomyController(ProductTaxonomyService taxonomy, ProductAdminWebMapper mapper) {
        this.taxonomy = taxonomy;
        this.mapper = mapper;
    }

    @GetMapping("/brands")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<BrandResponse>> brands(@RequestParam(name = "q", required = false) String search,
                                                           @RequestParam(required = false) Boolean active,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(required = false) Integer size,
                                                           @RequestParam(required = false) String sort) {
        return ApiResponse.ok(taxonomy.brands(search, active, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(mapper::toResponse));
    }

    @GetMapping("/brands/{brandId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<BrandResponse> brand(@PathVariable UUID brandId) {
        return ApiResponse.ok(mapper.toResponse(taxonomy.brand(brandId)));
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.CREATE)
    public ApiResponse<BrandResponse> createBrand(@Valid @RequestBody CreateBrandRequest request) {
        return ApiResponse.ok(mapper.toResponse(taxonomy.createBrand(request.code(), request.name(), request.logoUrl())));
    }

    @PutMapping("/brands/{brandId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<BrandResponse> updateBrand(@PathVariable UUID brandId,
                                                  @Valid @RequestBody UpdateBrandRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                taxonomy.updateBrand(brandId, request.name(), request.logoUrl(), request.active())));
    }

    @GetMapping("/categories")
    @Operation(summary = "List categories: all, the roots (roots=true) or the children of parentId")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<PageResponse<CategoryResponse>> categories(
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "false") boolean roots,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(taxonomy.categories(search, parentId, roots, active, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/categories/{categoryId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<CategoryResponse> category(@PathVariable UUID categoryId) {
        return ApiResponse.ok(mapper.toResponse(taxonomy.category(categoryId)));
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.CREATE)
    public ApiResponse<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest r) {
        return ApiResponse.ok(mapper.toResponse(taxonomy.createCategory(r.code(), r.parentId(),
                new CategoryDetails(r.name(), Objects.requireNonNullElse(r.sortOrder(), 0), r.imageUrl(),
                        r.seoTitle(), r.seoDescription(), true))));
    }

    @PutMapping("/categories/{categoryId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<CategoryResponse> updateCategory(@PathVariable UUID categoryId,
                                                        @Valid @RequestBody UpdateCategoryRequest r) {
        return ApiResponse.ok(mapper.toResponse(taxonomy.updateCategory(categoryId,
                new CategoryDetails(r.name(), Objects.requireNonNullElse(r.sortOrder(), 0), r.imageUrl(),
                        r.seoTitle(), r.seoDescription(), r.active()))));
    }
}
