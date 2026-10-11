package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.ShelfRequests;
import com.stockflow.warehouse.internal.controller.dto.ShelfResponse;
import com.stockflow.warehouse.internal.service.ShelfLayoutService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
@Tag(name = "Warehouse map: shelves", description = "Shelves, their levels and their bins")
@RequestMapping("/api/v1")
class ShelfController {

    private final ShelfLayoutService shelves;
    private final ShelfWebMapper mapper;

    ShelfController(ShelfLayoutService shelves, ShelfWebMapper mapper) {
        this.shelves = shelves;
        this.mapper = mapper;
    }

    @PostMapping("/warehouses/{warehouseId}/shelves")
    @Operation(summary = "Place a shelf on the map",
            description = "code is upper-cased and never changes (BR-13). Off the map: 409 "
                + "LAYOUT_OUT_OF_BOUNDS (BR-06). Overlapping a shelf or area that is not INACTIVE: "
                + "409 LAYOUT_OVERLAP (BR-07). A code in use: 409 SHELF_CODE_ALREADY_EXISTS.")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse> create(@PathVariable UUID warehouseId,
                                             @Valid @RequestBody ShelfRequests.CreateShelf request) {
        return ApiResponse.ok(mapper.toResponse(shelves.createShelf(mapper.toCommand(warehouseId, request))));
    }

    @GetMapping("/shelves/{shelfId}")
    @Operation(summary = "Look up one shelf with its levels and bins")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ApiResponse<ShelfResponse> get(@PathVariable UUID shelfId) {
        return ApiResponse.ok(mapper.toResponse(shelves.getShelf(shelfId)));
    }

    @PutMapping("/shelves/{shelfId}")
    @Operation(summary = "Move, resize or edit a shelf",
            description = "A full replacement; code cannot change. Off the map: 409 LAYOUT_OUT_OF_BOUNDS "
                + "(BR-06). Overlapping a shelf or area that is not INACTIVE: 409 LAYOUT_OVERLAP "
                + "(BR-07). Shrinking it under a bin: 409 LAYOUT_OUT_OF_BOUNDS. Changing "
                + "defaultStorageClass reclassifies every bin without an override. Send the version "
                + "from the last read; a stale one answers 409 OPTIMISTIC_LOCK.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse> update(@PathVariable UUID shelfId,
                                             @Valid @RequestBody ShelfRequests.UpdateShelf request) {
        return ApiResponse.ok(mapper.toResponse(shelves.updateShelf(mapper.toCommand(shelfId, request))));
    }

    @PutMapping("/shelves/{shelfId}/status")
    @Operation(summary = "Change a shelf's status",
            description = "Does not touch its bins: they read as unusable through effectiveStatus. Leaving "
                + "INACTIVE checks the place is still free (409 LAYOUT_OVERLAP otherwise).")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse> changeStatus(@PathVariable UUID shelfId,
                                                   @Valid @RequestBody ShelfRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(shelves.changeShelfStatus(shelfId, request.status())));
    }

    @PostMapping("/shelves/{shelfId}/levels")
    @Operation(summary = "Add a level to a shelf",
            description = "levelIndex is part of every bin's location code and never changes. At most 20 "
                + "levels a shelf (409 SHELF_CAPACITY_EXCEEDED).")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse.Level> addLevel(@PathVariable UUID shelfId,
                                                     @Valid @RequestBody ShelfRequests.AddLevel request) {
        return ApiResponse.ok(mapper.toResponse(shelves.addLevel(mapper.toCommand(shelfId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}")
    @Operation(summary = "Edit a level's measures",
            description = "The index cannot change.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Level> updateLevel(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                        @Valid @RequestBody ShelfRequests.UpdateLevel request) {
        return ApiResponse.ok(mapper.toResponse(shelves.updateLevel(mapper.toCommand(shelfId, levelId, request))));
    }

    @PostMapping("/shelves/{shelfId}/levels/{levelId}/bins")
    @Operation(summary = "Add one bin to a level",
            description = "Creates the bin's storage location, coded prefix-shelf-level-bin (HCM-A01-2-B). "
                + "Outside the shelf: 409 LAYOUT_OUT_OF_BOUNDS; overlapping another bin of the level: "
                + "409 LAYOUT_OVERLAP; pickable on a shelf with no pick face: 409 PICK_FACE_REQUIRED "
                + "(BR-08); more than 200 bins a level: 409 SHELF_CAPACITY_EXCEEDED.")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<ShelfResponse.Bin> addBin(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                 @Valid @RequestBody ShelfRequests.AddBin request) {
        return ApiResponse.ok(mapper.toResponse(shelves.addBin(mapper.toCommand(shelfId, levelId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}/bins/{binId}")
    @Operation(summary = "Edit a bin and its storage location",
            description = "The code cannot change.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Bin> updateBin(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                    @PathVariable UUID binId,
                                                    @Valid @RequestBody ShelfRequests.UpdateBin request) {
        return ApiResponse.ok(mapper.toResponse(
                shelves.updateBin(mapper.toCommand(shelfId, levelId, binId, request))));
    }

    @PutMapping("/shelves/{shelfId}/levels/{levelId}/bins/{binId}/status")
    @Operation(summary = "Change a bin's status",
            description = "Written to the bin's storage location.")
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.UPDATE)
    public ApiResponse<ShelfResponse.Bin> changeBinStatus(@PathVariable UUID shelfId, @PathVariable UUID levelId,
                                                          @PathVariable UUID binId,
                                                          @Valid @RequestBody ShelfRequests.ChangeStatus request) {
        return ApiResponse.ok(mapper.toResponse(shelves.changeBinStatus(shelfId, levelId, binId, request.status())));
    }

    /**
     * Fills empty levels with a {@code rowCount x columnCount} grid of equal bins (BR-14). All the
     * chosen levels or none: an unknown level answers {@code 404 SHELF_LEVEL_NOT_FOUND}, a level that
     * already has a bin - {@code INACTIVE} included - {@code 409 SHELF_LEVEL_HAS_BINS}, and more than
     * 200 bins a level {@code 409 SHELF_CAPACITY_EXCEEDED}.
     *
     * <p><b>Clients should send an {@code Idempotency-Key} header.</b> Without one, a double click
     * or a retry after a lost response fails the second time with {@code SHELF_LEVEL_HAS_BINS},
     * because the first already filled the levels; with one, it gets the first response back.</p>
     */
    @PostMapping("/shelves/{shelfId}/bin-generation")
    @Operation(summary = "Fill empty levels with a grid of equal bins",
            description = "BR-14. All the chosen levels or none: an unknown level is 404 "
                + "SHELF_LEVEL_NOT_FOUND, a level with any bin (INACTIVE included) 409 "
                + "SHELF_LEVEL_HAS_BINS, more than 200 bins a level 409 SHELF_CAPACITY_EXCEEDED. "
                + "Send an Idempotency-Key header: without one, a retry fails with "
                + "SHELF_LEVEL_HAS_BINS.")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.CREATE)
    public ApiResponse<List<ShelfResponse.GeneratedBin>> generateBins(
            @PathVariable UUID shelfId, @Valid @RequestBody ShelfRequests.GenerateBins request) {
        return ApiResponse.ok(mapper.toGeneratedBins(shelves.generateBins(mapper.toCommand(shelfId, request))));
    }
}
