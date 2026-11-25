package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignDecisionResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.controller.dto.DesignSnapshotResponse;
import com.stockflow.design.internal.domain.DesignReadRights;
import com.stockflow.design.internal.service.DesignWorkflowService;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/**
 * Reading designs and revising an approved one. A design is its owner's; someone who may review designs reads one
 * while it waits for review, and the sales staff one with a quote request open on it while it waits or once it is
 * approved (plan D7). Handlers are public so the permission proxy advises them; the resource is
 * declared once, on {@code DesignController}.
 */
@RestController
@RequestMapping("/api/v1/designs")
class DesignWorkflowController {
    private final DesignWorkflowService service;
    private final DesignWorkflowWebMapper mapper;

    DesignWorkflowController(DesignWorkflowService service, DesignWorkflowWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * The caller's own designs. {@code inProject=false} lists those in no design project - the ones a project may take
     * - and {@code true} those in one; absent, both. Filtered in the query, so a page and its total count agree.
     */
    @GetMapping
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignDraftResponse>> list(@AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) String status, @RequestParam(required = false) Boolean inProject,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(service.list(actor(user), status, inProject, pageOf(page, size))
                .map(mapper::toResponse)));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignDraftResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.get(id, actor(user), readRights(user))));
    }

    @GetMapping("/{id}/history")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignDecisionResponse>> history(@PathVariable UUID id,
            @AuthenticatedUser CurrentUser user, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(service.history(id, actor(user), readRights(user), pageOf(page, size))
                .map(mapper::toResponse)));
    }

    @GetMapping("/{id}/snapshot")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignSnapshotResponse> snapshot(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.snapshot(id, actor(user))));
    }

    /**
     * A revision of an approved design (§4.7), written into the project the optional body names ({@code {projectId}})
     * or else the source's own (BR-23.3). The body is optional so a client that sends none keeps working.
     */
    @PostMapping("/{id}/revisions")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> fork(@PathVariable UUID id,
            @RequestBody(required = false) RevisionRequest body, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.fork(id, actor(user), body == null ? null : body.projectId())));
    }

    /** The project to write a revision into; absent, the source's own. */
    record RevisionRequest(UUID projectId) {
    }

    static UUID actor(CurrentUser user) { return user == null ? null : user.userId(); }

    /**
     * What the caller may read beyond their own designs (plan D7): a reviewer reads a design waiting for review, and
     * whoever reads quote requests reads a design one is open on while it waits or once it is approved.
     */
    static DesignReadRights readRights(CurrentUser user) {
        return user == null ? DesignReadRights.NONE : new DesignReadRights(
                user.can(DesignResources.DESIGNS, Action.APPROVE), user.can(DesignResources.QUOTE_REQUESTS, Action.READ));
    }

    /** Validated here, not clamped by Spring's resolver: an oversized request is a 400, never a short page. */
    private static Pageable pageOf(int page, Integer size) {
        return Pages.of(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size);
    }
}
