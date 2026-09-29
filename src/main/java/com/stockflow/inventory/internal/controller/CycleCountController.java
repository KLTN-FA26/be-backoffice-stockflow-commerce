package com.stockflow.inventory.internal.controller;
import com.stockflow.common.api.*;
import com.stockflow.common.security.*;
import com.stockflow.inventory.internal.controller.dto.*;
import com.stockflow.inventory.internal.service.CycleCountService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/inventory/cycle-counts")
@PermissionResource(code=InventoryResources.CYCLE_COUNTS,group="Inventory",label="Cycle counts",route="/inventory/cycle-counts",
        apiPath="/api/v1/inventory/cycle-counts",actions={Action.VIEW_PAGE,Action.READ,Action.CREATE,Action.UPDATE,Action.APPROVE})
class CycleCountController {
    private final CycleCountService service;
    private final CycleCountWebMapper mapper;
    CycleCountController(CycleCountService service,CycleCountWebMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.READ)
    public ApiResponse<PageResponse<CycleCountResponse>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.list(page,size).map(mapper::toResponse));}
    @GetMapping("/{id}")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.READ)
    public ApiResponse<CycleCountResponse> get(@PathVariable UUID id){return ApiResponse.ok(mapper.toResponse(service.get(id)));}
    @PostMapping
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.CREATE)
    public ApiResponse<CycleCountResponse> create(@Valid @RequestBody CycleCountRequests.Create r){return ApiResponse.ok(mapper.toResponse(service.create(r.requestId(),r.warehouse(),r.assignedTo(),r.stockIds(),r.note())));}
    @PostMapping("/{id}/start")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.UPDATE)
    public ApiResponse<CycleCountResponse> start(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.start(id,r.version())));}
    @PutMapping("/{id}/lines/{stockId}")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.UPDATE)
    public ApiResponse<CycleCountResponse> record(@PathVariable UUID id,@PathVariable UUID stockId,@Valid @RequestBody CycleCountRequests.Record r){return ApiResponse.ok(mapper.toResponse(service.record(id,stockId,r.version(),r.quantity(),r.reason())));}
    @PostMapping("/{id}/submit")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.UPDATE)
    public ApiResponse<CycleCountResponse> submit(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.submit(id,r.version())));}
    @PostMapping("/{id}/approval")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.APPROVE)
    public ApiResponse<CycleCountResponse> approve(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.approve(id,r.version())));}
    @PostMapping("/{id}/posting")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.APPROVE)
    public ApiResponse<CycleCountResponse> post(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.post(id,r.version())));}
    @PostMapping("/{id}/rejection")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.APPROVE)
    public ApiResponse<CycleCountResponse> reject(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Reason r){return ApiResponse.ok(mapper.toResponse(service.reject(id,r.version(),r.reason())));}
    @PutMapping("/{id}/assignment")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.APPROVE)
    public ApiResponse<CycleCountResponse> reassign(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Assignment r){return ApiResponse.ok(mapper.toResponse(service.reassign(id,r.version(),r.assignedTo(),r.reason())));}
    @PostMapping("/{id}/recount")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.UPDATE)
    public ApiResponse<CycleCountResponse> recount(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Reason r){return ApiResponse.ok(mapper.toResponse(service.recount(id,r.version(),r.reason())));}
    @PostMapping("/{id}/cancellation")
    @RequiresPermission(resource=InventoryResources.CYCLE_COUNTS,action=Action.UPDATE)
    public ApiResponse<CycleCountResponse> cancel(@PathVariable UUID id,@Valid @RequestBody CycleCountRequests.Reason r){return ApiResponse.ok(mapper.toResponse(service.cancel(id,r.version(),r.reason())));}
}
