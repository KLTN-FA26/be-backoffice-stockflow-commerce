package com.stockflow.inventory.internal.controller;
import com.stockflow.common.api.*;
import com.stockflow.common.security.*;
import com.stockflow.inventory.internal.controller.dto.*;
import com.stockflow.inventory.internal.service.InventoryAlertService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/inventory/alerts")
@PermissionResource(code=InventoryResources.ALERTS,group="Inventory",label="Inventory alerts",route="/inventory/alerts",
        apiPath="/api/v1/inventory/alerts",actions={Action.VIEW_PAGE,Action.READ,Action.UPDATE})
class StockAlertController {
    private final InventoryAlertService service;private final StockAlertWebMapper mapper;
    StockAlertController(InventoryAlertService service,StockAlertWebMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping
    @RequiresPermission(resource=InventoryResources.ALERTS,action=Action.READ)
    public ApiResponse<PageResponse<StockAlertResponse>> list(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.list(status,page,size).map(mapper::toResponse));}
    @GetMapping("/{id}")
    @RequiresPermission(resource=InventoryResources.ALERTS,action=Action.READ)
    public ApiResponse<StockAlertResponse> get(@PathVariable UUID id){return ApiResponse.ok(mapper.toResponse(service.get(id)));}
    @PostMapping("/{id}/acknowledgement")
    @RequiresPermission(resource=InventoryResources.ALERTS,action=Action.UPDATE)
    public ApiResponse<StockAlertResponse> acknowledge(@PathVariable UUID id){return ApiResponse.ok(mapper.toResponse(service.acknowledge(id)));}
    @GetMapping("/{id}/deliveries")
    @RequiresPermission(resource=InventoryResources.ALERTS,action=Action.READ)
    public ApiResponse<List<AlertDeliveryResponse>> deliveries(@PathVariable UUID id){return ApiResponse.ok(service.deliveries(id).stream().map(mapper::toResponse).toList());}
    @PostMapping("/{id}/delivery-retry")
    @RequiresPermission(resource=InventoryResources.ALERTS,action=Action.UPDATE)
    public ApiResponse<Void> retry(@PathVariable UUID id){service.retry(id);return ApiResponse.ok(null);}
}
