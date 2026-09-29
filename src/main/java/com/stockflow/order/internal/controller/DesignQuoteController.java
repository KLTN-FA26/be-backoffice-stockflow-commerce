package com.stockflow.order.internal.controller;
import com.stockflow.common.api.*;
import com.stockflow.common.security.*;
import com.stockflow.order.internal.controller.dto.*;
import com.stockflow.order.internal.service.DesignQuoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/design-quotes")
@PermissionResource(code="sales-quotes",group="Sales",label="Design quotations",route="/sales/design-quotes",apiPath="/api/v1/design-quotes",
        actions={Action.VIEW_PAGE,Action.READ,Action.CREATE,Action.UPDATE,Action.APPROVE})
class DesignQuoteController {
    private final DesignQuoteService service;private final DesignQuoteWebMapper mapper;
    DesignQuoteController(DesignQuoteService service,DesignQuoteWebMapper mapper){this.service=service;this.mapper=mapper;}
    @GetMapping @RequiresPermission(resource="sales-quotes",action=Action.READ)
    public ApiResponse<PageResponse<DesignQuoteResponse>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.list(page,size).map(mapper::toResponse));}
    @GetMapping("/{id}") @RequiresPermission(resource="sales-quotes",action=Action.READ)
    public ApiResponse<DesignQuoteResponse> get(@PathVariable UUID id){return ApiResponse.ok(mapper.toResponse(service.get(id)));}
    @GetMapping("/{id}/revisions") @RequiresPermission(resource="sales-quotes",action=Action.READ)
    public ApiResponse<PageResponse<DesignQuoteResponse.Offer>> history(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return ApiResponse.ok(service.history(id,page,size).map(mapper::toResponse));}
    @PostMapping @RequiresPermission(resource="sales-quotes",action=Action.CREATE)
    public ApiResponse<DesignQuoteResponse> create(@Valid @RequestBody DesignQuoteRequests.Create r){return ApiResponse.ok(mapper.toResponse(service.create(r.requestId(),r.customerId(),r.designSnapshotId(),r.sku(),r.quantity(),r.unitPrice(),r.validUntil(),r.terms())));}
    @PostMapping("/{id}/revisions") @RequiresPermission(resource="sales-quotes",action=Action.APPROVE)
    public ApiResponse<DesignQuoteResponse> revise(@PathVariable UUID id,@Valid @RequestBody DesignQuoteRequests.Revision r){return ApiResponse.ok(mapper.toResponse(service.revise(id,r.version(),r.quantity(),r.unitPrice(),r.validUntil(),r.terms())));}
    @PostMapping("/{id}/issuance") @RequiresPermission(resource="sales-quotes",action=Action.APPROVE)
    public ApiResponse<DesignQuoteResponse> issue(@PathVariable UUID id,@Valid @RequestBody DesignQuoteRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.issue(id,r.version())));}
    @PostMapping("/{id}/acceptance") @RequiresPermission(resource="sales-quotes",action=Action.UPDATE,scope=DataScope.OWN)
    public ApiResponse<DesignQuoteResponse> accept(@PathVariable UUID id,@Valid @RequestBody DesignQuoteRequests.Version r){return ApiResponse.ok(mapper.toResponse(service.accept(id,r.version())));}
    @PostMapping("/{id}/change-requests") @RequiresPermission(resource="sales-quotes",action=Action.UPDATE,scope=DataScope.OWN)
    public ApiResponse<DesignQuoteResponse> requestChanges(@PathVariable UUID id,@Valid @RequestBody DesignQuoteRequests.Reason r){return ApiResponse.ok(mapper.toResponse(service.requestChanges(id,r.version(),r.reason())));}
    @PostMapping("/{id}/cancellation") @RequiresPermission(resource="sales-quotes",action=Action.APPROVE)
    public ApiResponse<DesignQuoteResponse> cancel(@PathVariable UUID id,@Valid @RequestBody DesignQuoteRequests.Reason r){return ApiResponse.ok(mapper.toResponse(service.cancel(id,r.version(),r.reason())));}
}
