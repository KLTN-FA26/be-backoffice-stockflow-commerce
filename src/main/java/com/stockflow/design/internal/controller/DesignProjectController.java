package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.design.internal.controller.dto.ArtifactDownloadResponse;
import com.stockflow.design.internal.controller.dto.DesignBatchSubmittedResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectAssetResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectEventResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectQuoteRequestResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectSummaryResponse;
import com.stockflow.design.internal.controller.dto.DesignProjectTextResponse;
import com.stockflow.design.internal.domain.QuoteRequestRules;
import com.stockflow.design.internal.domain.TextStyle;
import com.stockflow.design.internal.service.DesignBatchSubmissionService;
import com.stockflow.design.internal.service.DesignProjectAssetService;
import com.stockflow.design.internal.service.DesignProjectQuoteRequestService;
import com.stockflow.design.internal.service.DesignProjectService;
import com.stockflow.design.internal.service.DesignProjectTextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A customer's design projects (kltn-docs 12 §4.9-§4.11): the project and its designs, its library, a batch submitted
 * for review, a quote asked over its approved designs - and the sales staff's list of project quote requests. Handlers
 * are public so the permission proxy advises them; the resources are declared on {@code DesignController} and
 * {@code DesignQuoteRequestController}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Design projects", description = "A customer's designs gathered in a project, its library and batches")
class DesignProjectController {

    private final DesignProjectService projects;
    private final DesignProjectAssetService library;
    private final DesignBatchSubmissionService batches;
    private final DesignProjectQuoteRequestService quotes;
    private final DesignProjectTextService texts;
    private final DesignWorkflowWebMapper drafts;

    DesignProjectController(DesignProjectService projects, DesignProjectAssetService library,
                            DesignBatchSubmissionService batches, DesignProjectQuoteRequestService quotes,
                            DesignProjectTextService texts, DesignWorkflowWebMapper drafts) {
        this.projects = projects;
        this.library = library;
        this.batches = batches;
        this.quotes = quotes;
        this.texts = texts;
        this.drafts = drafts;
    }

    /** A colour as a layout writes one: {@code #rgb}, {@code #rrggbb} or {@code #rrggbbaa}. */
    private static final String HEX_COLOR = "^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$";

    record CreateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {
    }

    record DescribeRequest(@PositiveOrZero long version, @NotBlank @Size(max = 200) String name,
                           @Size(max = 2000) String description) {
    }

    record VersionRequest(@PositiveOrZero long version) {
    }

    record DesignRef(@NotNull UUID designId) {
    }

    record DesignAtVersion(@NotNull UUID id, @PositiveOrZero long version) {
    }

    /** At most a project's designs (B42), so a request names nothing a project cannot hold. */
    record BatchRequest(@NotNull @Size(min = 2, max = 20) List<@Valid @NotNull DesignAtVersion> designs) {
    }

    record QuoteLine(@NotNull UUID designId, @Min(1) @Max(QuoteRequestRules.MAX_CARTONS) int cartons) {
    }

    /** An image of one of the project's designs, to save into the library. */
    record FromDesignRequest(@NotNull UUID designId, @NotNull UUID assetId) {
    }

    record OutlineRequest(@NotNull @Pattern(regexp = HEX_COLOR) String color, @NotNull @Positive Double widthMm) {
    }

    /** The type fields of a layout's text element; {@code bold}, {@code italic} and the spacing default to none. */
    record TextStyleRequest(@NotBlank @Size(max = 64) String fontCode, @NotNull @Positive Double sizePt,
                            @NotNull @Pattern(regexp = HEX_COLOR) String color, Boolean bold, Boolean italic,
                            @NotNull @Pattern(regexp = "left|center|right") String align, Double letterSpacingEm,
                            @NotNull @Positive Double lineHeight, @Valid OutlineRequest outline) {

        TextStyle toStyle() {
            return new TextStyle(fontCode.strip(), sizePt, color, Boolean.TRUE.equals(bold),
                    Boolean.TRUE.equals(italic), align, letterSpacingEm == null ? 0 : letterSpacingEm, lineHeight,
                    outline == null ? null : new TextStyle.Outline(outline.color(), outline.widthMm()));
        }
    }

    record TextRequest(@NotBlank @Size(max = 500) String text, @NotNull @Valid TextStyleRequest style) {
    }

    /**
     * A finding of the studio's preflight. {@code message} is accepted for the studio's sake and never stored: it is
     * the browser's free text (Q-7).
     */
    record ReportedIssue(@NotBlank @Size(max = 64) String rule, @Size(max = 64) String surfaceCode,
                         @Size(max = 64) String elementId, @Size(max = 500) String message) {
    }

    record FailedDesign(@NotNull UUID designId,
                        @NotNull @Size(min = 1, max = 50) List<@Valid @NotNull ReportedIssue> issues) {
    }

    /** At most a project's designs (B42). */
    record PreflightFailureRequest(@NotNull @Size(min = 1, max = 20) List<@Valid @NotNull FailedDesign> items) {
    }

    record ProjectQuoteRequest(@NotNull @Size(min = 1, max = 20) List<@Valid @NotNull QuoteLine> lines,
                               LocalDate desiredDeliveryDate) {
    }

    @PostMapping("/design-projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a design project; the owner is the caller")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.CREATE)
    public ApiResponse<DesignProjectResponse> create(@Valid @RequestBody CreateRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.create(actor(user), request.name(), request.description())));
    }

    @GetMapping("/design-projects")
    @Operation(summary = "The caller's design projects, the newest first; status ACTIVE or ARCHIVED")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignProjectSummaryResponse>> list(@AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(projects.list(actor(user), status, pageOf(page, size))
                .map(p -> new DesignProjectSummaryResponse(p.id(), p.name(), p.description(), p.status().name(),
                        p.version(), p.designCount(), p.createdAt(), p.archivedAt()))));
    }

    @GetMapping("/design-projects/{id}")
    @Operation(summary = "A design project with its designs side by side")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<DesignProjectResponse> get(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.get(id, actor(user))));
    }

    @PutMapping("/design-projects/{id}")
    @Operation(summary = "Rename or describe an open project")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignProjectResponse> describe(@PathVariable UUID id,
            @Valid @RequestBody DescribeRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.describe(id, actor(user), request.version(), request.name(),
                request.description())));
    }

    @PostMapping("/design-projects/{id}/archive")
    @Operation(summary = "Close a project for good, once none of its designs waits for review")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignProjectResponse> archive(@PathVariable UUID id,
            @Valid @RequestBody VersionRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.archive(id, actor(user), request.version())));
    }

    @PostMapping("/design-projects/{id}/designs")
    @Operation(summary = "Add one of the caller's designs to the project")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignProjectResponse> addDesign(@PathVariable UUID id, @Valid @RequestBody DesignRef request,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.addDesign(id, actor(user), request.designId())));
    }

    @DeleteMapping("/design-projects/{id}/designs/{designId}")
    @Operation(summary = "Remove a design from the project; never one waiting for review")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignProjectResponse> removeDesign(@PathVariable UUID id, @PathVariable UUID designId,
                                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(projects.removeDesign(id, actor(user), designId)));
    }

    @GetMapping("/design-projects/{id}/history")
    @Operation(summary = "The project's history, the newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<PageResponse<DesignProjectEventResponse>> history(@PathVariable UUID id,
            @AuthenticatedUser CurrentUser user, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(projects.history(id, actor(user), pageOf(page, size))
                .map(e -> new DesignProjectEventResponse(e.actorId(), e.event(), e.notes(), e.createdAt()))));
    }

    /** As a design's own images: spooled, scanned, measured and stored; the same hourly allowance. */
    @PostMapping(path = "/design-projects/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file into the project's library (multipart part 'file')")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "design-uploads")
    public ApiResponse<DesignProjectAssetResponse> upload(@PathVariable UUID id, @AuthenticatedUser CurrentUser user,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(toResponse(library.upload(id, actor(user), upload, requestKey)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    /** A server-side copy of one of the design's images (E-2); the same image again answers with the file it made. */
    @PostMapping("/design-projects/{id}/assets/from-design")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save an image of one of the project's designs into its library")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "design-uploads")
    public ApiResponse<DesignProjectAssetResponse> saveFromDesign(@PathVariable UUID id,
            @Valid @RequestBody FromDesignRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(library.saveFromDesign(id, actor(user), request.designId(),
                request.assetId())));
    }

    @GetMapping("/design-projects/{id}/texts")
    @Operation(summary = "The texts of the project's library, the newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignProjectTextResponse>> texts(@PathVariable UUID id,
                                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(texts.list(id, actor(user)).stream().map(DesignProjectController::toResponse).toList());
    }

    @PostMapping("/design-projects/{id}/texts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a text with its type style into the project's library")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignProjectTextResponse> addText(@PathVariable UUID id,
            @Valid @RequestBody TextRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(texts.add(id, actor(user), request.text(), request.style().toStyle())));
    }

    @DeleteMapping("/design-projects/{id}/texts/{textId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a text from the library; designs it was placed on keep their copy")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public void removeText(@PathVariable UUID id, @PathVariable UUID textId, @AuthenticatedUser CurrentUser user) {
        texts.remove(id, textId, actor(user));
    }

    /**
     * Records that the studio's own preflight refused a batch (E-4): one {@code BATCH_PREFLIGHT_FAILED} row of the
     * project's history, nothing else changed. Rate-limited, since the findings are the browser's word.
     */
    @PostMapping("/design-projects/{id}/preflight-failures")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record that the studio's preflight refused a batch of the project's designs")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    @RateLimit(limit = 60, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "design-preflight-failures")
    public ApiResponse<DesignProjectEventResponse> recordPreflightFailure(@PathVariable UUID id,
            @Valid @RequestBody PreflightFailureRequest request, @AuthenticatedUser CurrentUser user) {
        Map<UUID, List<DesignProjectService.ReportedIssue>> issues = new LinkedHashMap<>();
        request.items().forEach(item -> issues.computeIfAbsent(item.designId(), designId -> new ArrayList<>())
                .addAll(item.issues().stream().map(i -> new DesignProjectService.ReportedIssue(i.rule().strip(),
                        blankToNull(i.surfaceCode()), blankToNull(i.elementId()))).toList()));
        var event = projects.recordPreflightFailure(id, actor(user), issues);
        return ApiResponse.ok(new DesignProjectEventResponse(event.actorId(), event.event(), event.notes(),
                event.createdAt()));
    }

    @GetMapping("/design-projects/{id}/assets")
    @Operation(summary = "The project's library, the newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignProjectAssetResponse>> assets(@PathVariable UUID id,
                                                                @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(library.list(id, actor(user)).stream().map(DesignProjectController::toResponse)
                .toList());
    }

    /** A signed link: never cached, never logged. */
    @GetMapping("/design-projects/{id}/assets/{assetId}/download-url")
    @Operation(summary = "A short-lived link to show a library file from")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ResponseEntity<ApiResponse<ArtifactDownloadResponse>> assetLink(@PathVariable UUID id,
            @PathVariable UUID assetId, @AuthenticatedUser CurrentUser user) {
        var link = library.link(id, assetId, actor(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new ArtifactDownloadResponse(link.url(), link.expiresAt())));
    }

    @DeleteMapping("/design-projects/{id}/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a file from the library; designs it was placed on keep their copy")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public void removeAsset(@PathVariable UUID id, @PathVariable UUID assetId, @AuthenticatedUser CurrentUser user) {
        library.remove(id, assetId, actor(user));
    }

    /**
     * Submits two or more drafts of the project together (kltn-docs 12 §4.10): all or none. 422
     * {@code DESIGN_BATCH_PREFLIGHT_FAILED} with {@code details.designs} when any does not pass.
     */
    @PostMapping("/design-projects/{id}/review-rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit drafts of the project together for review: all of them or none")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignBatchSubmittedResponse> submitBatch(@PathVariable UUID id,
            @Valid @RequestBody BatchRequest request, @AuthenticatedUser CurrentUser user) {
        UUID round = batches.submit(id, actor(user), request.designs().stream()
                .map(d -> new DesignBatchSubmissionService.DesignAtVersion(d.id(), d.version())).toList());
        return ApiResponse.ok(new DesignBatchSubmittedResponse(round, toResponse(projects.get(id, actor(user)))));
    }

    @PostMapping("/design-projects/{id}/quote-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask one quote over approved designs of the project")
    @RequiresPermission(resource = DesignResources.QUOTE_REQUESTS, action = Action.CREATE)
    public ApiResponse<DesignProjectQuoteRequestResponse> requestQuote(@PathVariable UUID id,
            @Valid @RequestBody ProjectQuoteRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(quotes.request(id, actor(user), request.lines().stream()
                .map(l -> new DesignProjectQuoteRequestService.LineInput(l.designId(), l.cartons())).toList(),
                request.desiredDeliveryDate())));
    }

    @GetMapping("/design-projects/{id}/quote-requests")
    @Operation(summary = "The project's quote requests, the newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignProjectQuoteRequestResponse>> quoteRequests(@PathVariable UUID id,
                                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(quotes.ofProject(id, actor(user)).stream().map(DesignProjectController::toResponse)
                .toList());
    }

    @GetMapping("/design-project-quote-requests")
    @Operation(summary = "Customers' project quote requests, the newest first")
    @RequiresPermission(resource = DesignResources.QUOTE_REQUESTS, action = Action.READ)
    public ApiResponse<PageResponse<DesignProjectQuoteRequestResponse>> salesList(
            @RequestParam(required = false) UUID customerId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(quotes.list(customerId, pageOf(page, size))
                .map(DesignProjectController::toResponse)));
    }

    private DesignProjectResponse toResponse(DesignProjectService.ProjectView p) {
        return new DesignProjectResponse(p.id(), p.name(), p.description(), p.status().name(), p.version(),
                p.createdAt(), p.archivedAt(), p.designs().stream().map(drafts::toResponse).toList(), p.assetCount());
    }

    private static DesignProjectAssetResponse toResponse(DesignProjectAssetService.LibraryAssetView a) {
        return new DesignProjectAssetResponse(a.assetId(), a.projectId(), a.originalName(), a.contentType(),
                a.sizeBytes(), a.checksum(), a.widthPx(), a.heightPx(), a.storedAt());
    }

    private static DesignProjectTextResponse toResponse(DesignProjectTextService.LibraryTextView t) {
        return new DesignProjectTextResponse(t.id(), t.projectId(), t.text(), t.style(), t.createdAt());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static DesignProjectQuoteRequestResponse toResponse(
            DesignProjectQuoteRequestService.ProjectQuoteRequestView r) {
        return new DesignProjectQuoteRequestResponse(r.id(), r.projectId(), r.projectName(), r.customerId(),
                r.customerName(), r.desiredDeliveryDate(), r.requestedBy(), r.requestedAt(), r.lines().stream()
                .map(l -> new DesignProjectQuoteRequestResponse.Line(l.designId(), l.designName(),
                        l.designStatus().name(), l.customerSku(), l.cartons())).toList());
    }

    private static org.springframework.data.domain.Pageable pageOf(int page, Integer size) {
        return Pages.of(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size);
    }

    private static UUID actor(CurrentUser user) {
        return user == null ? null : user.userId();
    }
}
