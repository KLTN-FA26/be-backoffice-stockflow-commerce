package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.internal.service.StockConsumption;
import com.stockflow.inventory.internal.controller.dto.ReservationResponse;
import com.stockflow.inventory.internal.controller.dto.ReserveStockRequest;
import com.stockflow.inventory.internal.controller.dto.StockItemResponse;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for inventory.
 *
 * <p><b>Thin by rule.</b> It converts a request into a command, calls the service, converts the
 * result into a response. No transaction, no business rule, no repository — {@code ArchitectureTest}
 * rejects a controller that imports anything from {@code internal.domain} or
 * {@code internal.repository}.</p>
 *
 * <p><b>The {@code @PermissionResource} block is a catalog row, not documentation.</b> It declares
 * which screen this endpoint group backs, its frontend route, and which of the seven actions it
 * supports. {@code PermissionCatalogScanner} reads these at startup to build the permission matrix
 * the admin screen renders, so the matrix is generated from the code that actually serves the
 * request rather than maintained by hand in a seed script that drifts.</p>
 *
 * <p><b>Handler methods are public.</b> {@code @RequiresPermission} and {@code @Auditable} are
 * applied by Spring AOP proxies, which advise a package-private method only when the generated
 * proxy happens to land in the same package and classloader. When that does not hold the advice is
 * skipped silently - and a skipped {@code @RequiresPermission} is an endpoint with no authorisation
 * at all. Public is the only form where the guard is guaranteed to run.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Stock on hand, availability and reservations")
@PermissionResource(
        code = InventoryResources.STOCK_ITEMS,
        group = "Inventory",
        label = "Stock on hand",
        route = "/inventory/stock",
        apiPath = "/api/v1/inventory/stock-items",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class InventoryController {

    private final InventoryService inventoryService;
    private final StockConsumption stockConsumption;
    private final InventoryWebMapper mapper;

    InventoryController(InventoryService inventoryService, StockConsumption stockConsumption,
                        InventoryWebMapper mapper) {
        this.inventoryService = inventoryService;
        this.stockConsumption = stockConsumption;
        this.mapper = mapper;
    }

    @GetMapping("/stock-items")
    @Operation(summary = "Stock for one SKU, broken down by location, earliest expiry first")
    @RequiresPermission(resource = InventoryResources.STOCK_ITEMS,
            action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<List<StockItemResponse>> stockItems(@RequestParam("sku") String sku) {
        return ApiResponse.ok(mapper.toResponses(inventoryService.availabilityOf(new Sku(sku))));
    }

    @GetMapping("/stock-items/atp")
    @Operation(summary = "Available to promise for one SKU across every location")
    @RequiresPermission(resource = InventoryResources.STOCK_ITEMS, action = Action.READ)
    public ApiResponse<Integer> availableToPromise(@RequestParam("sku") String sku) {
        return ApiResponse.ok(inventoryService.availableToPromise(new Sku(sku)));
    }

    /**
     * Reserve stock over HTTP.
     *
     * <p>This endpoint exists for operations tooling and for a future external caller. The
     * checkout path does <b>not</b> go through it — {@code order} calls
     * {@code InventoryService.reserve(...)} as a Java method inside its own transaction. Routing an
     * internal call through the network stack would give up exactly the atomicity that made the
     * monolith worth building.</p>
     */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Hold stock for an order line")
    @RequiresPermission(resource = InventoryResources.RESERVATIONS, action = Action.CREATE)
    public ApiResponse<ReservationResponse> reserve(@Valid @RequestBody ReserveStockRequest request) {
        ReserveStockResult result = inventoryService.reserve(new ReserveStockCommand(
                request.requestId(), new Sku(request.sku()), request.quantity(), request.orderId()));
        return ApiResponse.ok(mapper.toResponse(result));
    }

    /**
     * Confirm a pick: the goods have left the shelf, so deduct them for real.
     *
     * <p>Called by {@code fulfillment} over HTTP rather than as a Java call, because unlike the
     * checkout reservation it does <b>not</b> need to be atomic with anything on the caller's side.
     * The pick already happened physically; recording it is a fact, not a negotiation. Guarded by
     * {@code UPDATE} rather than {@code CREATE}: it changes an existing hold, it does not make one.
     *
     * <p>A sub-resource ({@code /consumption}) rather than {@code PATCH /reservations/{id}} with a
     * status field, so the permission model can distinguish "may confirm a pick" from "may edit a
     * reservation" — those are different people in a warehouse.</p>
     */
    @PostMapping("/reservations/{reservationId}/consumption")
    @Operation(summary = "Deduct the stock a reservation was holding, after picking")
    @RequiresPermission(resource = InventoryResources.RESERVATIONS,
            action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<Void> consume(@PathVariable UUID reservationId) {
        stockConsumption.consume(reservationId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/reservations/{reservationId}")
    @Operation(summary = "Release a hold")
    @RequiresPermission(resource = InventoryResources.RESERVATIONS, action = Action.DELETE)
    public ApiResponse<Void> release(@PathVariable UUID reservationId,
                              @RequestParam(value = "reason", required = false) String reason) {
        inventoryService.release(reservationId, reason);
        return ApiResponse.ok(null);
    }
}
