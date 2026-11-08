package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignReviewRoundResponse;
import com.stockflow.design.internal.controller.dto.DesignRoundApprovalResponse;
import com.stockflow.design.internal.service.DesignBatchSubmissionService;
import com.stockflow.design.internal.service.DesignReviewRoundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects' review rounds, for the Sales Manager (kltn-docs 12 §4.10): one item each in the queue, opened with their
 * designs side by side; each design is decided on its own ({@code POST /designs/{id}/reviews}), or all those still
 * waiting approved in turn. Handlers are public so the permission proxy advises them; the resource is declared on
 * {@code DesignController}.
 */
@RestController
@RequestMapping("/api/v1/design-review-rounds")
@Tag(name = "Design review rounds", description = "Designs of a project submitted together, waiting for review")
class DesignReviewRoundController {

    private final DesignReviewRoundService rounds;
    private final DesignWorkflowWebMapper drafts;

    DesignReviewRoundController(DesignReviewRoundService rounds, DesignWorkflowWebMapper drafts) {
        this.rounds = rounds;
        this.drafts = drafts;
    }

    record DesignAtVersion(@NotNull UUID id, @PositiveOrZero long version) {
    }

    /** The round's designs at the versions the Sales Manager saw (BR-11). */
    record ApprovalRequest(@NotNull @Size(min = 1, max = 20) List<@Valid @NotNull DesignAtVersion> designs) {
    }

    @GetMapping
    @Operation(summary = "Projects' review rounds waiting, the longest waiting first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<PageResponse<DesignReviewRoundResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(rounds.queue(Pages.of(page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)).map(this::toResponse)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "A review round with its designs side by side")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<DesignReviewRoundResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(rounds.get(id)));
    }

    /** "Duyệt tất cả": each design still waiting approved in turn; one that cannot be stays waiting (BR-20). */
    @PostMapping("/{id}/approvals")
    @Operation(summary = "Approve every design of the round still waiting, one after the other")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<DesignRoundApprovalResponse> approveAll(@PathVariable UUID id,
            @Valid @RequestBody ApprovalRequest request, @AuthenticatedUser CurrentUser user) {
        var outcomes = rounds.approveAll(id, DesignWorkflowController.actor(user), request.designs().stream()
                .map(d -> new DesignBatchSubmissionService.DesignAtVersion(d.id(), d.version())).toList());
        return ApiResponse.ok(new DesignRoundApprovalResponse(outcomes.stream()
                .map(o -> new DesignRoundApprovalResponse.Outcome(o.designId(), o.outcome(), o.errorCode(),
                        o.message())).toList(), toResponse(rounds.get(id))));
    }

    private DesignReviewRoundResponse toResponse(DesignReviewRoundService.RoundView r) {
        return new DesignReviewRoundResponse(r.id(), r.projectId(), r.projectName(), r.customerId(), r.customerName(),
                r.submittedBy(), r.submittedAt(), r.closedAt(), r.designs().stream()
                .map(d -> new DesignReviewRoundResponse.Design(drafts.toResponse(d.design()), d.outcome(),
                        d.decidedAt())).toList());
    }
}
