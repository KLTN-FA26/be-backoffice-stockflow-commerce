package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.AttributeResponse;
import com.stockflow.product.internal.controller.dto.CreateAttributeRequest;
import com.stockflow.product.internal.controller.dto.CreateAttributeValueRequest;
import com.stockflow.product.internal.controller.dto.UpdateAttributeRequest;
import com.stockflow.product.internal.controller.dto.UpdateAttributeValueRequest;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.service.AttributeDictionaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The attribute dictionary (WBS 3.1.2). Adding, changing or removing a value is an UPDATE of the
 * attribute, not a CREATE/DELETE of a resource of its own: a value has no life outside its attribute,
 * so whoever may edit the attribute may edit its values, and no separate grant is needed. Hard deletes
 * take the version the caller last read, so a delete based on a stale view answers 409.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Attributes", description = "The attribute dictionary and its values")
@PermissionResource(
        code = ProductResources.ATTRIBUTES,
        group = "Product",
        label = "Attributes",
        route = "/admin/attributes",
        apiPath = "/api/v1/attributes",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
class AttributeController {

    private final AttributeDictionaryService attributes;
    private final AttributeWebMapper mapper;

    AttributeController(AttributeDictionaryService attributes, AttributeWebMapper mapper) {
        this.attributes = attributes;
        this.mapper = mapper;
    }

    @GetMapping("/attributes")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.READ)
    public ApiResponse<PageResponse<AttributeResponse>> list(
            @RequestParam(name = "q", required = false) String search,
            @RequestParam(required = false) AttributeDataType dataType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(attributes.list(search, dataType, active, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/attributes/{attributeId}")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.READ)
    public ApiResponse<AttributeResponse> get(@PathVariable UUID attributeId) {
        return ApiResponse.ok(mapper.toResponse(attributes.get(attributeId)));
    }

    @PostMapping("/attributes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.CREATE)
    public ApiResponse<AttributeResponse> create(@Valid @RequestBody CreateAttributeRequest r) {
        return ApiResponse.ok(mapper.toResponse(attributes.create(r.code(), r.dataType(),
                new AttributeDetails(r.name(), r.unit(), r.filterable(), r.searchable(),
                        Objects.requireNonNullElse(r.sortOrder(), 0)))));
    }

    @PutMapping("/attributes/{attributeId}")
    @Operation(summary = "Update an attribute",
            description = "Send the version from the last read; a stale one answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.UPDATE)
    public ApiResponse<AttributeResponse> update(@PathVariable UUID attributeId,
                                                 @Valid @RequestBody UpdateAttributeRequest r) {
        return ApiResponse.ok(mapper.toResponse(attributes.update(attributeId, r.version(),
                new AttributeDetails(r.name(), r.unit(), r.filterable(), r.searchable(),
                        Objects.requireNonNullElse(r.sortOrder(), 0)), r.active())));
    }

    @DeleteMapping("/attributes/{attributeId}")
    @Operation(summary = "Delete an attribute for good",
            description = "version is required (400 without it); a stale one answers 409 OPTIMISTIC_LOCK and "
                    + "an attribute a product still uses answers 409 ATTRIBUTE_IN_USE.")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.DELETE)
    public ApiResponse<Void> delete(@PathVariable UUID attributeId,
                                    @Parameter(description = "The version from the last read")
                                    @RequestParam long version) {
        attributes.delete(attributeId, version);
        return ApiResponse.ok();
    }

    @PostMapping("/attributes/{attributeId}/values")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.UPDATE)
    public ApiResponse<AttributeResponse> addValue(@PathVariable UUID attributeId,
                                                   @Valid @RequestBody CreateAttributeValueRequest r) {
        return ApiResponse.ok(mapper.toResponse(attributes.addValue(attributeId, r.code(),
                new AttributeValueDetails(r.label(), r.valueNumber(), r.hexColor(),
                        Objects.requireNonNullElse(r.sortOrder(), 0)))));
    }

    @PutMapping("/attributes/{attributeId}/values/{valueId}")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.UPDATE)
    public ApiResponse<AttributeResponse> updateValue(@PathVariable UUID attributeId, @PathVariable UUID valueId,
                                                      @Valid @RequestBody UpdateAttributeValueRequest r) {
        return ApiResponse.ok(mapper.toResponse(attributes.updateValue(attributeId, valueId, r.version(),
                new AttributeValueDetails(r.label(), r.valueNumber(), r.hexColor(),
                        Objects.requireNonNullElse(r.sortOrder(), 0)), r.active())));
    }

    @DeleteMapping("/attributes/{attributeId}/values/{valueId}")
    @Operation(summary = "Remove a value from an attribute",
            description = "version is required (400 without it). Answers the attribute without the value.")
    @RequiresPermission(resource = ProductResources.ATTRIBUTES, action = Action.UPDATE)
    public ApiResponse<AttributeResponse> deleteValue(@PathVariable UUID attributeId, @PathVariable UUID valueId,
                                                      @Parameter(description = "The version of the value from the last read")
                                                      @RequestParam long version) {
        return ApiResponse.ok(mapper.toResponse(attributes.deleteValue(attributeId, valueId, version)));
    }
}
