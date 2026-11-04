package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignDecisionResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.service.DesignWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** Assisted creation is privileged because CRM-to-login ownership is not inferred from client data. */
@RestController
@RequestMapping("/api/v1/designs")
@PermissionResource(code = "design-administration", group = "Design", label = "Design administration",
        route = "/designs/administration", apiPath = "/api/v1/designs", actions = {Action.CREATE, Action.UPDATE})
class DesignWorkflowController {
    private final DesignWorkflowService service;
    private final DesignWorkflowWebMapper mapper;
    DesignWorkflowController(DesignWorkflowService service, DesignWorkflowWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }
    record CreateRequest(@NotNull UUID customerId, @NotNull UUID productId, @NotNull UUID ownerUserId,
            UUID assignedUserId, @NotNull UUID reviewerUserId, @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4000) String spec) { }
    record ReviseRequest(@PositiveOrZero long version, @NotBlank @Size(max = 4000) String spec) { }
    record ReviewRequest(@PositiveOrZero long version, @NotNull UUID artifactId, boolean passed,
            @NotBlank @Size(max = 2000) String notes) { }
    record VersionRequest(@PositiveOrZero long version) { }
    record ConfirmRequest(@PositiveOrZero long version, @NotNull UUID artifactId) { }
    record ChangeRequest(@PositiveOrZero long version, @NotBlank @Size(max = 2000) String reason) { }
    record AssignmentRequest(@PositiveOrZero long version, UUID designerId, @NotNull UUID reviewerId) { }

    @PutMapping("/{id}/assignment")
    @RequiresPermission(resource = "design-administration", action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> assign(@PathVariable UUID id,
            @Valid @RequestBody AssignmentRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.assign(id, actor(user), r.version(), r.designerId(), r.reviewerId())));
    }

    @PostMapping
    @RequiresPermission(resource = "design-administration", action = Action.CREATE)
    public ApiResponse<DesignDraftResponse> create(@Valid @RequestBody CreateRequest r,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.create(r.customerId(), r.productId(), r.ownerUserId(), r.assignedUserId(),
                r.reviewerUserId(), r.name(), r.spec(), actor(user))));
    }
    @GetMapping
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignDraftResponse>> list(@AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(service.list(actor(user), pageOf(page, size)).map(mapper::toResponse)));
    }
    @GetMapping("/{id}")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignDraftResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.get(id, actor(user))));
    }
    @GetMapping("/{id}/history")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignDecisionResponse>> history(@PathVariable UUID id, @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(service.history(id, actor(user), pageOf(page, size)).map(mapper::toResponse)));
    }
    @GetMapping("/{id}/snapshot")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignSnapshotResponse> snapshot(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.snapshot(id, actor(user))));
    }
    @PostMapping("/{id}/confirmation-withdrawals")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> withdraw(@PathVariable UUID id,
            @Valid @RequestBody ChangeRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.withdraw(id, actor(user), r.version(), r.reason())));
    }
    @PutMapping("/{id}/specification")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> revise(@PathVariable UUID id,
            @Valid @RequestBody ReviseRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.revise(id, actor(user), r.version(), r.spec())));
    }
    @PostMapping("/{id}/technical-reviews")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<DesignDraftResponse> review(@PathVariable UUID id,
            @Valid @RequestBody ReviewRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.review(id, actor(user), r.version(), r.artifactId(), r.passed(), r.notes())));
    }
    @PostMapping("/{id}/confirmation-requests")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> submit(@PathVariable UUID id,
            @Valid @RequestBody VersionRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.submit(id, actor(user), r.version())));
    }
    @PostMapping("/{id}/change-requests")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> changes(@PathVariable UUID id,
            @Valid @RequestBody ChangeRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.requestChanges(id, actor(user), r.version(), r.reason())));
    }
    @PostMapping("/{id}/confirmations")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignSnapshotResponse> confirm(@PathVariable UUID id,
            @Valid @RequestBody ConfirmRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.confirm(id, actor(user), r.version(), r.artifactId())));
    }
    @PostMapping("/{id}/revisions")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> fork(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.fork(id, actor(user))));
    }
    private static UUID actor(CurrentUser user) { return user == null ? null : user.userId(); }

    /** Validated here, not clamped by Spring's resolver: an oversized request is a 400, never a short page. */
    private static Pageable pageOf(int page, Integer size) {
        return Pages.of(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size);
    }
}
