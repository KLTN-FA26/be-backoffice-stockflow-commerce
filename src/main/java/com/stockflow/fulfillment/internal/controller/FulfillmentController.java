package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Roles;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentArtifactAccessResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Task-scoped warehouse flow with a non-bypassable design-integrity gate. */
@RestController
@RequestMapping("/api/v1/fulfillment/tasks")
@PermissionResource(code = FulfillmentResources.PICK_LISTS, group = "Fulfillment", label = "Pick lists",
        route = "/fulfillment/picks", apiPath = "/api/v1/fulfillment/tasks",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class FulfillmentController {

    private final FulfillmentService fulfillment;
    private final FulfillmentWebMapper mapper;

    FulfillmentController(FulfillmentService fulfillment, FulfillmentWebMapper mapper) {
        this.fulfillment = fulfillment;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.ORDER_COORDINATOR + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> create(@Valid @RequestBody CreateTaskRequest request,
                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.createTask(request.orderId(), request.assignedUserId(), actor)));
    }

    @GetMapping
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER
            + "','" + Roles.QC_STAFF + "','" + Roles.ORDER_COORDINATOR + "')")
    public ApiResponse<PageResponse<FulfillmentTaskResponse>> list(@AuthenticatedUser CurrentUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(fulfillment
                .listTasks(actor, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @PutMapping("/{taskId}/assignment")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.ORDER_COORDINATOR + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> assign(@PathVariable UUID taskId,
            @Valid @RequestBody AssignmentRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.assign(taskId, request.assignedUserId(), actor)));
    }

    @GetMapping("/{taskId}")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<FulfillmentTaskResponse> find(@PathVariable UUID taskId,
                                             @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.findTask(taskId, actor)));
    }

    @PostMapping("/{taskId}/picking-start")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> startPicking(@PathVariable UUID taskId,
                                                     @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.startPicking(taskId, actor)));
    }

    @PostMapping("/{taskId}/picking-completion")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> completePicking(@PathVariable UUID taskId,
                                                        @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePicking(taskId, actor)));
    }

    @GetMapping("/{taskId}/lines/{lineId}/design-artifacts/{role}/download-url")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER
            + "','" + Roles.QC_STAFF + "')")
    public ResponseEntity<ApiResponse<FulfillmentArtifactAccessResponse>> artifactDownload(
            @PathVariable UUID taskId, @PathVariable UUID lineId, @PathVariable String role,
            @AuthenticatedUser CurrentUser actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(fulfillment.artifactDownload(taskId, lineId, role, actor))));
    }

    @PostMapping("/{taskId}/packing-completion")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> completePacking(@PathVariable UUID taskId,
                                                        @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePacking(taskId, actor)));
    }

    @PostMapping("/{taskId}/design-integrity-reverification")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.QC_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "')")
    public ApiResponse<FulfillmentTaskResponse> reverify(@PathVariable UUID taskId,
            @Valid @RequestBody ReverificationRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.reverifyDesignIntegrity(taskId, request.resolutionNote(), actor)));
    }

    record CreateTaskRequest(@NotNull UUID orderId, @NotNull UUID assignedUserId) { }
    record AssignmentRequest(@NotNull UUID assignedUserId) { }
    record ReverificationRequest(@NotBlank @Size(max = 1000) String resolutionNote) { }
}
