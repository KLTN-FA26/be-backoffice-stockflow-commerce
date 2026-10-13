package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.GenerateVariantsRequest;
import com.stockflow.product.internal.controller.dto.GeneratedVariantsResponse;
import com.stockflow.product.internal.controller.dto.VariantRequest;
import com.stockflow.product.internal.controller.dto.VariantResponse;
import com.stockflow.product.internal.service.ProductVariantService;
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

import java.util.UUID;

/**
 * The variants of a product (WBS 3.1.2). A variant's id is the {@code skuId} of the SKU routes
 * ({@code /skus/{skuId}/logistics}, {@code /skus/{skuId}/inventory-control}).
 *
 * <p>Variants have their own permission resource, {@code product-variants} (declared on
 * {@link ProductAttributeController}, which guards the axes the same way), rather than
 * {@code product-products}: who may define a sellable SKU is not who may edit product content. Reads
 * need READ; adding a variant and generating the matrix create rows, so CREATE; changing or blocking
 * one is UPDATE; putting one on sale or retiring it is APPROVE.</p>
 *
 * <p>Update is partial: a null {@code optionValueIds} keeps the options, a blank sku or name keeps
 * the current value, and options change only while the variant is DRAFT. The default variant takes only
 * the matrix-first combination while it is DRAFT with signature DEFAULT; a SKU sent with that
 * combination renames it. A rename the database refuses because the SKU is referenced answers 409
 * INVALID_VARIANT_TRANSITION. Handlers are public so the permission proxy is certain to advise them.</p>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants")
@Tag(name = "Product variants", description = "Sellable SKUs of a product")
class ProductVariantController {

    private final ProductVariantService variants;
    private final ProductAdminWebMapper mapper;

    ProductVariantController(ProductVariantService variants, ProductAdminWebMapper mapper) {
        this.variants = variants;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List a product's variants, by position")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<PageResponse<VariantResponse>> list(@PathVariable UUID productId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(variants.list(productId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/{variantId}")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.READ)
    public ApiResponse<VariantResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.get(productId, variantId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a DRAFT variant for one option per axis; it creates the SKU's inventory item",
            description = "Only the matrix-first combination (first option of each axis, axes in order) goes to "
                    + "the default variant, and only while it is DRAFT with signature DEFAULT; a SKU sent with it "
                    + "renames that variant. Any other combination is a new row.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.CREATE)
    public ApiResponse<VariantResponse> add(@PathVariable UUID productId, @Valid @RequestBody VariantRequest request) {
        return ApiResponse.ok(mapper.toResponse(variants.add(productId, command(request))));
    }

    @PostMapping("/generation")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the missing variants of a product's option matrix",
            description = "Without a body, every option of every axis is used; optionValueIds restricts the "
                    + "axes it names. The default variant takes only the matrix-first combination while it is "
                    + "DRAFT with signature DEFAULT; every other combination is a new row. Combinations that "
                    + "already exist are skipped and counted. At most 100 combinations are processed per call, "
                    + "the default's included, else 400 VARIANT_MATRIX_TOO_LARGE. Idempotent.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.CREATE)
    public ApiResponse<GeneratedVariantsResponse> generate(
            @PathVariable UUID productId, @Valid @RequestBody(required = false) GenerateVariantsRequest request) {
        return ApiResponse.ok(mapper.toResponse(
                variants.generate(productId, request == null ? null : request.optionValueIds())));
    }

    @PutMapping("/{variantId}")
    @Operation(summary = "Partial update: rename, reorder, and while DRAFT change the SKU or options",
            description = "A null optionValueIds keeps the options; a blank sku or name keeps the current value. "
                    + "A rename refused because the SKU is referenced is 409 INVALID_VARIANT_TRANSITION.")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.UPDATE)
    public ApiResponse<VariantResponse> update(@PathVariable UUID productId, @PathVariable UUID variantId,
                                               @Valid @RequestBody VariantRequest request) {
        return ApiResponse.ok(mapper.toResponse(variants.update(productId, variantId, command(request))));
    }

    @PostMapping("/{variantId}/activation")
    @Operation(summary = "Put a variant on sale (product approved or published)")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.APPROVE)
    public ApiResponse<VariantResponse> activate(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.activate(productId, variantId)));
    }

    @PostMapping("/{variantId}/blocking")
    @Operation(summary = "Take a variant off sale for now")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.UPDATE)
    public ApiResponse<VariantResponse> block(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.block(productId, variantId)));
    }

    @PostMapping("/{variantId}/obsoletion")
    @Operation(summary = "Retire a variant for good (not the default one)")
    @RequiresPermission(resource = ProductResources.VARIANTS, action = Action.APPROVE)
    public ApiResponse<VariantResponse> obsolete(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(variants.obsolete(productId, variantId)));
    }

    private static ProductVariantService.NewVariant command(VariantRequest r) {
        return new ProductVariantService.NewVariant(r.sku(), r.name(), r.optionValueIds(), r.position());
    }
}
