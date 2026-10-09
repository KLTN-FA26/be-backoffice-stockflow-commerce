package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.ShelfRequests;
import com.stockflow.warehouse.internal.controller.dto.ShelfResponse;
import com.stockflow.warehouse.internal.service.ShelfLayoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Shelves, their levels and their bins. Guarded by {@link WarehouseResources#LOCATIONS}, declared on
 * {@link WarehouseLocationResourceDeclaration}.
 *
 * <p>Placing, moving or resizing a shelf answers {@code 409 LAYOUT_OUT_OF_BOUNDS} (BR-06) or
 * {@code 409 LAYOUT_OVERLAP} (BR-07); a pickable bin on a shelf without a pick face answers
 * {@code 409 PICK_FACE_REQUIRED} (BR-08).</p>
 */
@RestController
@RequestMapping("/api/v1")
class ShelfController {

    private final ShelfLayoutService shelves;
    private final ShelfWebMapper mapper;

    ShelfController(ShelfLayoutService shelves, ShelfWebMapper mapper) {
        this.shelves = shelves;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/shelves")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse> create(@PathVariable UUID warehouseId,
                                             @Valid @RequestBody ShelfRequests.CreateShelf request) {
        return ApiResponse.ok(mapper.toResponse(shelves.createShelf(mapper.toCommand(warehouseId, request))));
    }

    @GetMapping("/shelves/{shelfId}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ApiResponse<ShelfResponse> get(@PathVariable UUID shelfId) {
        return ApiResponse.ok(mapper.toResponse(shelves.getShelf(shelfId)));
    }

    @PutMapping("/shelves/{shelfId}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse> update(@PathVariable UUID shelfId,
                                             @Valid @RequestBody ShelfRequests.UpdateShelf request) {
        return ApiResponse.ok(mapper.toResponse(shelves.updateShelf(mapper.toCommand(shelfId, request))));
    }

    @PutMapping("/shelves/{shelfId}/status")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse> changeStatus(@PathVariable UUID shelfId,
                                                   @Valid @RequestBody ShelfRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(shelves.changeShelfStatus(shelfId, request.status())));
    }

    @PostMapping("/shelves/{shelfId}/levels")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse.Level> addLevel(@PathVariable UUID shelfId,
                                                     @Valid @RequestBody ShelfRequests.AddLevel request) {
        return ApiResponse.ok(mapper.toResponse(shelves.addLevel(mapper.toCommand(shelfId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Level> updateLevel(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                        @Valid @RequestBody ShelfRequests.UpdateLevel request) {
        return ApiResponse.ok(mapper.toResponse(shelves.updateLevel(mapper.toCommand(shelfId, levelId, request))));
    }

    @PostMapping("/shelves/{shelfId}/levels/{levelId}/bins")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse.Bin> addBin(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                 @Valid @RequestBody ShelfRequests.AddBin request) {
        return ApiResponse.ok(mapper.toResponse(shelves.addBin(mapper.toCommand(shelfId, levelId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}/bins/{binId}")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Bin> updateBin(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                    @PathVariable UUID binId,
                                                    @Valid @RequestBody ShelfRequests.UpdateBin request) {
        return ApiResponse.ok(mapper.toResponse(
                shelves.updateBin(mapper.toCommand(shelfId, levelId, binId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}/bins/{binId}/status")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Bin> changeBinStatus(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                          @PathVariable UUID binId,
                                                          @Valid @RequestBody ShelfRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(shelves.changeBinStatus(shelfId, levelId, binId, request.status())));
    }
}
