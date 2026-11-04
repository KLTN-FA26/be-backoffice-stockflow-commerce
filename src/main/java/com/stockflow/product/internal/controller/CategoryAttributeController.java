package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.CategoryAttributeResponse;
import com.stockflow.product.internal.controller.dto.ReplaceCategoryAttributesRequest;
import com.stockflow.product.internal.service.CategoryAttributeService;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A category's suggested attribute set. It is guarded by the {@code product-attributes} resource that
 * {@link AttributeController} declares, so it declares nothing itself: the set belongs to the attribute
 * dictionary, and a second declaration of the same code would be a duplicate in the permission catalog.
 */
@RestController
@RequestMapping("/api/v1/categories/{categoryId}/attributes")
@Tag(name = "Attributes", description = "The attribute dictionary and its values")
class CategoryAttributeController {

    private final CategoryAttributeService categoryAttributes;
    private final AttributeWebMapper mapper;

    CategoryAttributeController(CategoryAttributeService categoryAttributes, AttributeWebMapper mapper) {
        this.categoryAttributes = categoryAttributes;
        this.mapper = mapper;
    }

    @GetMapping
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.READ)
    public ApiResponse<List<CategoryAttributeResponse>> list(@PathVariable UUID categoryId) {
        return ApiResponse.ok(categoryAttributes.list(categoryId).stream().map(mapper::toResponse).toList());
    }

    @PutMapping
    @Operation(summary = "Replace the attribute set of a category",
            description = "The list is the whole set: attributes left out are removed, an empty list clears it.")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.UPDATE)
    public ApiResponse<List<CategoryAttributeResponse>> replace(@PathVariable UUID categoryId,
                                                                @Valid @RequestBody ReplaceCategoryAttributesRequest r) {
        List<CategoryAttributeEntry> entries = r.attributes().stream()
                .map(e -> new CategoryAttributeEntry(e.attributeId(), e.defaultRole(), e.required(),
                        Objects.requireNonNullElse(e.sortOrder(), 0)))
                .toList();
        return ApiResponse.ok(categoryAttributes.replace(categoryId, entries).stream().map(mapper::toResponse).toList());
    }
}
