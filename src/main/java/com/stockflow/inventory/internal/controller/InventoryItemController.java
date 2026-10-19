package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.internal.controller.dto.InventoryItemResponse;
import com.stockflow.inventory.internal.controller.dto.InventoryItemSummaryResponse;
import com.stockflow.inventory.internal.controller.dto.UpdateItemLogisticsRequest;
import com.stockflow.inventory.internal.controller.dto.UpdateItemPolicyRequest;
import com.stockflow.inventory.internal.service.InventoryItems;
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

import java.util.UUID;

/**
 * Inventory items by their own id. Logistics and stock policy are edited here, not under a product: the item is what
 * stock hangs off, and a product or variant is only one of the ways to reach it. Reading needs
 * {@code inventory-inventory-items:READ}; both PUTs need {@code :UPDATE}, which warehouse staff do not hold.
 *
 * <p>The resource declares only the actions an endpoint here checks. V20260928006000 also seeded CREATE and EXPORT
 * for it, but items are created by the variant trigger and nothing exports them, so the role matrix would offer
 * grants that guard nothing. Undeclared grants are left alone by the matrix rather than shown or removed.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/items")
@Tag(name = "Inventory items", description = "Logistics and stock policy of an item, addressed by its own id")
@PermissionResource(
        code = InventoryResources.INVENTORY_ITEMS,
        group = "Inventory",
        label = "Inventory items",
        route = "/inventory/items",
        apiPath = "/api/v1/inventory/items",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class InventoryItemController {

    private final InventoryItems items;
    private final InventoryItemWebMapper mapper;

    InventoryItemController(InventoryItems items, InventoryItemWebMapper mapper) {
        this.items = items;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List inventory items in SKU order; sku is exact, q matches SKU or barcode")
    @RequiresPermission(resource = InventoryResources.INVENTORY_ITEMS, action = Action.READ)
    public ApiResponse<PageResponse<InventoryItemSummaryResponse>> list(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) StorageClass storageClass,
            @RequestParam(required = false) Boolean policyConfigured,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Pages.DEFAULT_PAGE_SIZE) int size) {
        return ApiResponse.ok(items.search(
                new InventoryItems.ItemQuery(sku, q, storageClass, policyConfigured, page, size))
                .map(mapper::toSummary));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One inventory item: logistics, stock policy and the threshold evaluation")
    @RequiresPermission(resource = InventoryResources.INVENTORY_ITEMS, action = Action.READ)
    public ApiResponse<InventoryItemResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toResponse(items.get(id)));
    }

    @PutMapping("/{id}/logistics")
    @Operation(summary = "Replace the item's logistics; version is the item's, a stale one is 409")
    @RequiresPermission(resource = InventoryResources.INVENTORY_ITEMS, action = Action.UPDATE)
    public ApiResponse<InventoryItemResponse> describe(@PathVariable UUID id,
                                                       @Valid @RequestBody UpdateItemLogisticsRequest request) {
        return ApiResponse.ok(mapper.toResponse(items.describe(id, request.version(), mapper.toLogistics(request))));
    }

    @PutMapping("/{id}/policy")
    @Operation(summary = "Replace the item's stock policy; null thresholds clear them")
    @RequiresPermission(resource = InventoryResources.INVENTORY_ITEMS, action = Action.UPDATE)
    public ApiResponse<InventoryItemResponse> configure(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateItemPolicyRequest request) {
        return ApiResponse.ok(mapper.toResponse(items.configure(id, request.version(), mapper.toPolicy(request))));
    }
}
