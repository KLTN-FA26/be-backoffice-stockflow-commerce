package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.ProductAttributesResponse;
import com.stockflow.product.internal.controller.dto.ReplaceAxesRequest;
import com.stockflow.product.internal.controller.dto.ReplaceDescriptiveAttributesRequest;
import com.stockflow.product.internal.service.ProductAttributeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A product's variant axes and descriptive attributes. Axes are variant structure, so they are guarded
 * by {@code product-variants}, which this controller declares; descriptive attributes are product
 * content and stay under {@code product-products}, declared by {@link ProductController}.
 *
 * <p>Handlers are public so the permission proxy is certain to advise them.</p>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
@Tag(name = "Products", description = "Product master data")
@PermissionResource(
        code = ProductResources.VARIANTS,
        group = "Product",
        label = "Product variants",
        route = "/admin/products",
        apiPath = "/api/v1/products",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE, Action.EXPORT})
class ProductAttributeController {

    private final ProductAttributeService attributes;
    private final ProductAttributeWebMapper mapper;

    ProductAttributeController(ProductAttributeService attributes, ProductAttributeWebMapper mapper) {
        this.attributes = attributes;
        this.mapper = mapper;
    }

    @GetMapping("/attributes")
    @Operation(summary = "The axes of a product with their options, and its descriptive attributes",
            description = "Axes are ordered by their sort order; the options of an axis follow the dictionary "
                    + "(value sort order, then code).")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<ProductAttributesResponse> get(@PathVariable UUID productId) {
        return ApiResponse.ok(mapper.toResponse(attributes.get(productId)));
    }

    @PutMapping("/axes")
    @Operation(summary = "Replace the variant axes of a product",
            description = "The list is the whole set. Axes can only be added or removed while the product is a draft. "
                    + "The order of an axis's option values in the request is not stored; options are returned "
                    + "in dictionary order (value sort order, then code).")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.UPDATE)
    public ApiResponse<ProductAttributesResponse> replaceAxes(@PathVariable UUID productId,
                                                              @Valid @RequestBody ReplaceAxesRequest r) {
        return ApiResponse.ok(mapper.toResponse(attributes.replaceAxes(productId, mapper.toAxisSpecs(r))));
    }

    @PutMapping("/descriptive-attributes")
    @Operation(summary = "Replace the descriptive attributes of a product",
            description = "The list is the whole set; an empty list clears it.")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductAttributesResponse> replaceDescriptives(
            @PathVariable UUID productId, @Valid @RequestBody ReplaceDescriptiveAttributesRequest r) {
        return ApiResponse.ok(mapper.toResponse(
                attributes.replaceDescriptives(productId, mapper.toDescriptiveSpecs(r))));
    }
}
