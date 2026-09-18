package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
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
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** Assisted creation is privileged because CRM-to-login ownership is not inferred from client data. */
@RestController
@RequestMapping("/api/v1/designs")
@PermissionResource(code = "design-administration", group = "Design", label = "Design administration",
        route = "/designs/administration", apiPath = "/api/v1/designs", actions = {Action.CREATE, Action.UPDATE})
class DesignWorkflowController {
    private final DesignWorkflowService service;
    DesignWorkflowController(DesignWorkflowService service) { this.service = service; }
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
    public ApiResponse<DesignWorkflowService.DraftView> assign(@PathVariable UUID id,
            @Valid @RequestBody AssignmentRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.assign(id, actor(user), r.version(), r.designerId(), r.reviewerId()));
    }

    @PostMapping
    @RequiresPermission(resource = "design-administration", action = Action.CREATE)
    public ApiResponse<DesignWorkflowService.DraftView> create(@Valid @RequestBody CreateRequest r,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.create(r.customerId(), r.productId(), r.ownerUserId(), r.assignedUserId(),
                r.reviewerUserId(), r.name(), r.spec(), actor(user)));
    }
    @GetMapping
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<?> list(@AuthenticatedUser CurrentUser user, Pageable pageable) {
        return ApiResponse.ok(com.stockflow.common.persistence.Pages.toResponse(service.list(actor(user), pageable)));
    }
    @GetMapping("/{id}")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignWorkflowService.DraftView> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.get(id, actor(user)));
    }
    @GetMapping("/{id}/history")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<?> history(@PathVariable UUID id, @AuthenticatedUser CurrentUser user, Pageable pageable) {
        return ApiResponse.ok(com.stockflow.common.persistence.Pages.toResponse(service.history(id, actor(user), pageable)));
    }
    @GetMapping("/{id}/snapshot")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignWorkflowService.SnapshotView> snapshot(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.snapshot(id, actor(user)));
    }
    @PostMapping("/{id}/confirmation-withdrawals")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.DraftView> withdraw(@PathVariable UUID id,
            @Valid @RequestBody ChangeRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.withdraw(id, actor(user), r.version(), r.reason()));
    }
    @PutMapping("/{id}/specification")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.DraftView> revise(@PathVariable UUID id,
            @Valid @RequestBody ReviseRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.revise(id, actor(user), r.version(), r.spec()));
    }
    @PostMapping("/{id}/technical-reviews")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<DesignWorkflowService.DraftView> review(@PathVariable UUID id,
            @Valid @RequestBody ReviewRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.review(id, actor(user), r.version(), r.artifactId(), r.passed(), r.notes()));
    }
    @PostMapping("/{id}/confirmation-requests")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.DraftView> submit(@PathVariable UUID id,
            @Valid @RequestBody VersionRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.submit(id, actor(user), r.version()));
    }
    @PostMapping("/{id}/change-requests")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.DraftView> changes(@PathVariable UUID id,
            @Valid @RequestBody ChangeRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.requestChanges(id, actor(user), r.version(), r.reason()));
    }
    @PostMapping("/{id}/confirmations")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.SnapshotView> confirm(@PathVariable UUID id,
            @Valid @RequestBody ConfirmRequest r, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.confirm(id, actor(user), r.version(), r.artifactId()));
    }
    @PostMapping("/{id}/revisions")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignWorkflowService.DraftView> fork(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.fork(id, actor(user)));
    }
    private static UUID actor(CurrentUser user) { return user == null ? null : user.userId(); }
}
