package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignDocumentResponse;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.service.CustomerDesignService;
import com.stockflow.design.internal.service.DesignSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's own designs (kltn-docs 12 §4.1, §4.2): start one on a template, save its layout, rename it, delete it
 * while it is a draft, and read the template it is drawn on. Reading and listing designs, their history and revisions
 * stay on {@code DesignWorkflowController}. Handlers are public so the permission proxy advises them; the resource is
 * declared once, on {@code DesignController}.
 */
@RestController
@RequestMapping("/api/v1/designs")
@Tag(name = "Customer designs", description = "Designs a customer makes on their own in the storefront's studio")
class CustomerDesignController {

    private final CustomerDesignService service;
    private final DesignSubmissionService submissions;
    private final DesignWorkflowWebMapper drafts;
    private final CustomerDesignWebMapper mapper;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    CustomerDesignController(CustomerDesignService service, DesignSubmissionService submissions,
                             DesignWorkflowWebMapper drafts, CustomerDesignWebMapper mapper,
                             com.fasterxml.jackson.databind.ObjectMapper json) {
        this.service = service;
        this.submissions = submissions;
        this.drafts = drafts;
        this.mapper = mapper;
        this.json = json;
    }

    record StartRequest(@NotNull UUID productId, @NotBlank @Size(max = 64) String sku,
                        @NotBlank @Size(max = 50) String templateCode, @NotBlank @Size(max = 200) String name) {
    }

    record RenameRequest(@PositiveOrZero long version, @NotBlank @Size(max = 200) String name) {
    }

    record VersionRequest(@PositiveOrZero long version) {
    }

    /** A submission, and the quote request it carries if any ("Gửi duyệt và yêu cầu báo giá", kltn-docs 12 §4.8). */
    record SubmitRequest(@PositiveOrZero long version, @Valid DesignQuoteRequestController.QuoteRequestBody quoteRequest) {
    }

    @PostMapping("/mine")
    @Operation(summary = "Start a design on a variant's template; the customer and owner are the caller")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.CREATE)
    public ResponseEntity<ApiResponse<DesignDraftResponse>> start(@Valid @RequestBody StartRequest request,
                                                                  @AuthenticatedUser CurrentUser user) {
        var started = service.start(actor(user), new CustomerDesignService.StartDesign(request.productId(),
                request.sku(), request.templateCode(), request.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(drafts.toResponse(started)));
    }

    @PutMapping("/{id}/name")
    @Operation(summary = "Rename a draft")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(drafts.toResponse(service.rename(id, actor(user), request.version(), request.name())));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Delete a draft: it is archived and no longer shown")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> archive(@PathVariable UUID id, @Valid @RequestBody VersionRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(drafts.toResponse(service.archive(id, actor(user), request.version())));
    }

    /** The layout is private to its owner and changes on every save: never cached. */
    @GetMapping("/{id}/document")
    @Operation(summary = "The design's layout, with its checksum and the design version")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ResponseEntity<ApiResponse<DesignDocumentResponse>> document(@PathVariable UUID id,
                                                                        @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(service.document(id, actor(user),
                        DesignWorkflowController.readRights(user)))));
    }

    /**
     * The body is taken as text and read by the service, which needs the JSON exactly as sent (see
     * {@link CustomerDesignService#saveDocument}); its size is capped before it is read
     * ({@code stockflow.web.body-limits}).
     */
    @PutMapping(path = "/{id}/document", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save the design's layout: {version, document}; at most 1,000,000 bytes")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDocumentResponse> saveDocument(@PathVariable UUID id, @RequestBody String body,
                                                            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.saveDocument(id, actor(user), body)));
    }

    /**
     * Submits the draft for review (kltn-docs 12 §4.3): 422 {@code DESIGN_PREFLIGHT_FAILED} with the findings when the
     * layout fails the print checks, 409 {@code DESIGN_FILES_OUTDATED} when the submitted files are not rendered from
     * the current layout. A {@code quoteRequest} in the body is asked once the design is submitted - or, with a request
     * still open on a design returned after it, that request now asks this (B40) - and refuses the submission first when
     * it cannot be asked: 400 for its fields, 409 {@code CUSTOMER_NOT_ACTIVE}. 404 for anyone but the owner.
     */
    @PostMapping("/{id}/technical-review-requests")
    @Operation(summary = "Submit the draft for review, with a quote request if any: preflight, then the Sales "
            + "Manager's queue")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignDraftResponse> submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(drafts.toResponse(submissions.submit(id, actor(user), request.version(),
                request.quoteRequest() == null ? null : request.quoteRequest().input())));
    }

    /**
     * In the shape of one template of the storefront's P2, plus {@code active}: as it is now, or for an approved design
     * as it was approved.
     */
    @GetMapping("/{id}/template")
    @Operation(summary = "The template the design is drawn on: as it is now, or as approved")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ResponseEntity<ApiResponse<com.fasterxml.jackson.databind.JsonNode>> template(@PathVariable UUID id,
            @AuthenticatedUser CurrentUser user) throws com.fasterxml.jackson.core.JsonProcessingException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
                json.readTree(service.template(id, actor(user), DesignWorkflowController.readRights(user)))));
    }

    private static UUID actor(CurrentUser user) {
        return user == null ? null : user.userId();
    }
}
