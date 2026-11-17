package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ReprintReason;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.domain.SampleRounds;
import com.stockflow.order.internal.service.ProductionSampleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The print sample rounds of a production order, back office side (4A-4, kltn-docs 17 §4.3 step 9, BR-24): the
 * Production Coordinator uploads the photos and videos of each round - straight to the object store through
 * {@code /uploads}, then names the uploads - records when a physical sample went out, and classifies why a round is
 * printed again; Sales, the Sales Manager and the warehouse manager read them, with the warning when the rounds counted
 * pass the contract's free ones (14 BR-18). The customer's side is {@link CustomerProductionSampleController}.
 *
 * <p>The rounds carry no version: whether one may be opened or changed is decided under the production order's lock
 * from the rounds themselves (see {@code ProductionSampleService}). Handler methods are public, so the permission
 * aspect advises them.</p>
 */
@RestController
@RequestMapping("/api/v1/production-orders/{productionOrderId}/sample-rounds")
@Tag(name = "Production orders")
class ProductionSampleRoundController {

    /** Round 1, or a round printed again with why. */
    @Schema(name = "OpenSampleRoundRequest")
    record OpenRequest(@NotEmpty(message = "uploadIds is required")
                       @Size(max = SampleRounds.MEDIA_PER_UPLOAD_MAX, message = "at most 10 files at once")
                       List<@NotNull UUID> uploadIds,
                       @Schema(description = "The day a physical sample went to the customer, if one did")
                       LocalDate physicalSampleSentOn,
                       @Schema(description = "Null for round 1; PRINT_DEFECT (not counted against the free rounds) or "
                               + "CUSTOMER_CHANGE after")
                       ReprintReason reprintReason) {
    }

    /** More photos or videos for the round waiting for the customer. */
    @Schema(name = "SampleRoundMediaRequest")
    record MediaRequest(@NotEmpty(message = "uploadIds is required")
                        @Size(max = SampleRounds.MEDIA_PER_UPLOAD_MAX, message = "at most 10 files at once")
                        List<@NotNull UUID> uploadIds) {
    }

    /** The day a physical sample of the round went to the customer. */
    @Schema(name = "PhysicalSampleRequest")
    record PhysicalSampleRequest(@NotNull(message = "sentOn is required") LocalDate sentOn) {
    }

    /** A photo or video of a round; its bytes through {@code .../view-url}. */
    @Schema(name = "SampleMedia")
    record MediaResponse(UUID id, String fileName, String contentType, long sizeBytes, boolean video,
                         Instant uploadedAt) {

        static List<MediaResponse> of(SampleRound round) {
            return round.media().stream().map(m -> new MediaResponse(m.id(), m.fileName(), m.contentType(),
                    m.sizeBytes(), m.video(), m.uploadedAt())).toList();
        }
    }

    /** One round, as the back office sees it. */
    @Schema(name = "SampleRound")
    record RoundResponse(UUID id, int roundNo,
                         @Schema(description = "Why it was printed again; null for round 1") ReprintReason reprintReason,
                         @Schema(description = "Counted against the contract's free sample rounds (not PRINT_DEFECT)")
                         boolean counted,
                         LocalDate physicalSampleSentOn, UUID openedBy, Instant openedAt,
                         @Schema(description = "Null while it waits for the customer") SampleRound.Result result,
                         String customerNote, UUID answeredBy, Instant answeredAt, List<MediaResponse> media) {
    }

    /** A production order's rounds, and its contract's free sample rounds against those counted. */
    @Schema(name = "SampleRounds")
    record RoundsResponse(UUID productionOrderId, String code, ProductionOrderStatus status,
                          boolean sampleApprovalRequired, long version,
                          @Schema(description = "14 BR-18: the contract's free sample rounds; null when its contract "
                                  + "is gone") Integer freeSampleRounds,
                          int countedRounds,
                          @Schema(description = "Past the free rounds: warn the coordinator and Sales - never blocks")
                          boolean freeRoundsExceeded,
                          @Schema(description = "The round waiting for the customer, if one does") UUID waitingRoundId,
                          List<RoundResponse> rounds) {

        static RoundsResponse of(ProductionSampleService.RoundsView view) {
            var p = view.order();
            return new RoundsResponse(p.id(), p.code(), p.status(), p.sampleApprovalRequired(), p.version(),
                    view.allowance().free(), view.allowance().counted(), view.allowance().exceeded(),
                    view.rounds().waiting().map(SampleRound::id).orElse(null),
                    view.rounds().rounds().stream().map(r -> new RoundResponse(r.id(), r.roundNo(), r.reprintReason(),
                            r.counted(), r.physicalSampleSentOn(), r.openedBy(), r.openedAt(), r.result(),
                            r.customerNote(), r.answeredBy(), r.answeredAt(), MediaResponse.of(r))).toList());
        }
    }

    /** A short-lived link showing a photo or video in the page. Never logged. */
    @Schema(name = "SampleMediaLink")
    record LinkResponse(String url, Instant expiresAt) {
    }

    private final ProductionSampleService samples;

    ProductionSampleRoundController(ProductionSampleService samples) {
        this.samples = samples;
    }

    @GetMapping
    @Operation(summary = "A production order's print sample rounds, with the free-round warning")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<RoundsResponse> list(@PathVariable UUID productionOrderId, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(RoundsResponse.of(samples.rounds(productionOrderId)));
    }

    @PostMapping(path = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a print sample photo or video: a signed PUT for the declared file "
            + "(images, MP4, QuickTime, WebM; up to 100 MB)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID productionOrderId,
                                                               @Valid @RequestBody UploadSlotRequest request,
                                                               @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(samples.beginUpload(productionOrderId, request, user.userId())));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open the next print sample round with uploaded photos / videos: round 1 of an ACCEPTED "
            + "production order (it becomes SAMPLE_PENDING), or a round printed again after the customer asked, with "
            + "its reprint reason. The same uploadIds again answer the rounds as they are")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<RoundsResponse> open(@PathVariable UUID productionOrderId,
                                            @Valid @RequestBody OpenRequest request,
                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(RoundsResponse.of(samples.open(productionOrderId, request.reprintReason(),
                request.physicalSampleSentOn(), IncomingFiles.uploaded(user.userId(), request.uploadIds()),
                user.userId())));
    }

    @PostMapping(path = "/{roundId}/media", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add uploaded photos / videos to the round waiting for the customer")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<RoundsResponse> addMedia(@PathVariable UUID productionOrderId, @PathVariable UUID roundId,
                                                @Valid @RequestBody MediaRequest request,
                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(RoundsResponse.of(samples.addMedia(productionOrderId, roundId,
                IncomingFiles.uploaded(user.userId(), request.uploadIds()), user.userId())));
    }

    @PutMapping("/{roundId}/physical-sample")
    @Operation(summary = "Record the day a physical sample of the round went to the customer, while it waits")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<RoundsResponse> physicalSample(@PathVariable UUID productionOrderId, @PathVariable UUID roundId,
                                                      @Valid @RequestBody PhysicalSampleRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(RoundsResponse.of(samples.physicalSampleSent(productionOrderId, roundId,
                request.sentOn())));
    }

    @GetMapping("/{roundId}/media/{mediaId}/view-url")
    @Operation(summary = "A short-lived link showing one photo or video of a round")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ResponseEntity<ApiResponse<LinkResponse>> view(@PathVariable UUID productionOrderId,
                                                          @PathVariable UUID roundId, @PathVariable UUID mediaId,
                                                          @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var link = samples.mediaLink(productionOrderId, roundId, mediaId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new LinkResponse(link.url(), link.expiresAt())));
    }
}
