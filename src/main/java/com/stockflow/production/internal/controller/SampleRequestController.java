package com.stockflow.production.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.production.internal.controller.dto.CreateSampleRequestRequest;
import com.stockflow.production.internal.controller.dto.SampleCancellationRequest;
import com.stockflow.production.internal.controller.dto.SampleDraftRequest;
import com.stockflow.production.internal.controller.dto.SampleFeedbackRequest;
import com.stockflow.production.internal.controller.dto.SampleRequestResponse;
import com.stockflow.production.internal.controller.dto.SampleRequestRowResponse;
import com.stockflow.production.internal.controller.dto.SubmitSampleRequest;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.service.SampleRequests;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sample requests (SCRUM-394/403): Sales drafts a round, sends it to the workshop, records that the
 * sample went out, and the customer approves it or asks for changes.
 *
 * <p>Two kinds of write, two actions. UPDATE is Sales handling the round — edit, submit, record sending,
 * cancel. APPROVE is the customer's answer — approve or ask for changes — given on the portal or recorded
 * by Sales on their behalf (V20261014000200 grants it to CUSTOMER and SALES_STAFF only). Keeping the answer
 * off UPDATE is what stops anyone who merely works on a round, a QC inspector say, from approving a design
 * for mass print in the customer's place (BR-PRD-08). A portal customer is held to their own rounds by
 * the service, and the back-office verbs refuse a caller who is only a customer.</p>
 *
 * <p>Every endpoint keeps the default scope ALL on purpose. Declaring OWN would narrow the scope below
 * WAREHOUSE and so switch off the warehouse restriction of a workshop user; ownership is enforced in the
 * service instead, where the choice of scope cannot skip it.</p>
 */
@RestController
@RequestMapping("/api/v1/production/sample-requests")
@Tag(name = "Sample requests", description = "Samples of a new design, made for the customer to approve")
@PermissionResource(
        code = ProductionResources.SAMPLE_REQUESTS,
        group = "Production",
        label = "Sample requests",
        route = "/production/sample-requests",
        apiPath = "/api/v1/production/sample-requests",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class SampleRequestController {

    private final SampleRequests samples;
    private final ProductionWebMapper mapper;

    SampleRequestController(SampleRequests samples, ProductionWebMapper mapper) {
        this.samples = samples;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draft the first round of a sample for a customer's confirmed design on a blank")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.CREATE)
    public ApiResponse<SampleRequestResponse> create(@Valid @RequestBody CreateSampleRequestRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.create(user, new SampleRequests.Create(request.customerId(),
                request.quoteId(), new SampleRequests.Draft(request.designSnapshotId(), request.blankSku(),
                request.quantity(), request.dueDate())))));
    }

    @GetMapping
    @Operation(summary = "Sample rounds, newest first; a portal customer sees only their own")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.READ)
    public ApiResponse<PageResponse<SampleRequestRowResponse>> list(
            @RequestParam(name = "status", required = false) List<SampleStatus> statuses,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID designSnapshotId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(samples.list(user, new SampleRequests.Criteria(customerId, statuses, designSnapshotId,
                        search, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort))
                .map(mapper::toResponse));
    }

    @GetMapping("/{sampleRequestId}")
    @Operation(summary = "One round with every round of its request and its sample production order")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.READ)
    public ApiResponse<SampleRequestResponse> get(@PathVariable UUID sampleRequestId,
                                                  @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.get(user, sampleRequestId)));
    }

    @PutMapping("/{sampleRequestId}")
    @Operation(summary = "Change a draft round's design, blank, quantity or due date")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.UPDATE)
    public ApiResponse<SampleRequestResponse> edit(@PathVariable UUID sampleRequestId,
                                                   @Valid @RequestBody SampleDraftRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.edit(user, sampleRequestId, draft(request))));
    }

    @PostMapping("/{sampleRequestId}/submission")
    @Operation(summary = "Send a draft to the workshop: SUBMITTED, with its SAMPLE production order")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.UPDATE)
    public ApiResponse<SampleRequestResponse> submit(@PathVariable UUID sampleRequestId,
                                                     @Valid @RequestBody SubmitSampleRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.submit(user, sampleRequestId, request.warehouseId())));
    }

    @PostMapping("/{sampleRequestId}/sending")
    @Operation(summary = "Record that a READY sample was handed over or couriered to the customer")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.UPDATE)
    public ApiResponse<SampleRequestResponse> markSent(@PathVariable UUID sampleRequestId,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.markSent(user, sampleRequestId)));
    }

    @PostMapping("/{sampleRequestId}/approval")
    @Operation(summary = "The customer approves the sample: locks design + blank, announces SampleApproved")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.APPROVE)
    public ApiResponse<SampleRequestResponse> approve(@PathVariable UUID sampleRequestId,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.approve(user, sampleRequestId)));
    }

    @PostMapping("/{sampleRequestId}/change-request")
    @Operation(summary = "The customer asks for changes; the feedback stays on this round")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.APPROVE)
    public ApiResponse<SampleRequestResponse> requestChanges(@PathVariable UUID sampleRequestId,
                                                             @Valid @RequestBody SampleFeedbackRequest request,
                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.requestChanges(user, sampleRequestId, request.feedback())));
    }

    @PostMapping("/{sampleRequestId}/next-round")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start the next round after CHANGES_REQUESTED: a new DRAFT request, round + 1; "
            + "without a body it repeats this round's specification")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.CREATE)
    public ApiResponse<SampleRequestResponse> startNextRound(@PathVariable UUID sampleRequestId,
                                                             @Valid @RequestBody(required = false)
                                                             SampleDraftRequest request,
                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.startNextRound(user, sampleRequestId,
                request == null ? null : draft(request))));
    }

    @PostMapping("/{sampleRequestId}/cancellation")
    @Operation(summary = "Cancel a draft round, with a reason")
    @RequiresPermission(resource = ProductionResources.SAMPLE_REQUESTS, action = Action.UPDATE)
    public ApiResponse<SampleRequestResponse> cancel(@PathVariable UUID sampleRequestId,
                                                     @Valid @RequestBody SampleCancellationRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(samples.cancel(user, sampleRequestId, request.reason())));
    }

    private static SampleRequests.Draft draft(SampleDraftRequest request) {
        return new SampleRequests.Draft(request.designSnapshotId(), request.blankSku(), request.quantity(),
                request.dueDate());
    }
}
