package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.service.ProductionSampleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The storefront's print samples to approve (4A-4; kltn-docs 13 §4.3 step 5, BR-03, BR-13; 17 §4.3 step 9): the
 * signed-in customer's production orders whose print sample waits for them, each round's photos and videos, and the
 * one answer they give a round - approve, which starts mass printing, or ask for a reprint with a note.
 *
 * <p>The customer is resolved from the sign-in, never from the request; a round of another customer's production
 * order answers 404 as one that does not exist (13 BR-13), and so does every round for a customer whose profile is not
 * ACTIVE (13 BR-03: print samples are shown to ACTIVE customers only). The reprint reason the coordinator classified is
 * internal - it decides what the round costs - and is not shown here. The chat card (16, QD-8) is later; it will call
 * the same service. Handler methods are public, so the permission aspect advises them.</p>
 */
@RestController
@RequestMapping("/api/v1/customer/production-samples")
@Tag(name = "Production orders")
@PermissionResource(code = OrderResources.PRODUCTION_SAMPLES, group = "Production",
        label = "Storefront: print samples to approve", route = "/my-designs/print-samples",
        apiPath = "/api/v1/customer/production-samples", actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
class CustomerProductionSampleController {

    private static final String ACTIVE = "ACTIVE";

    /** What to change, for the round printed again. */
    @Schema(name = "ReprintRequest")
    record ReprintRequest(@NotBlank(message = "note is required")
                          @Size(max = 500, message = "note is at most 500 characters") String note) {
    }

    /** A line of the production order: the customer's own SKU and how much of it. */
    @Schema(name = "CustomerSampleLine")
    record LineResponse(String sku, int cartons, int unitsPerCarton) {
    }

    /** One round, as the customer sees it. */
    @Schema(name = "CustomerSampleRound")
    record RoundResponse(UUID id, int roundNo, Instant openedAt, LocalDate physicalSampleSentOn,
                         @Schema(description = "Null while it waits for the customer's answer") SampleRound.Result result,
                         String customerNote, Instant answeredAt,
                         List<ProductionSampleRoundController.MediaResponse> media) {
    }

    /** A production order of the customer's and its print sample rounds. */
    @Schema(name = "CustomerProductionSample")
    record SampleResponse(UUID productionOrderId, String code, UUID salesOrderId, ProductionOrderStatus status,
                          List<LineResponse> lines,
                          @Schema(description = "The round to answer, if one waits") UUID waitingRoundId,
                          List<RoundResponse> rounds) {

        static SampleResponse of(ProductionSampleService.RoundsView view) {
            var p = view.order();
            return new SampleResponse(p.id(), p.code(), p.salesOrderId(), p.status(),
                    p.lines().stream().map(l -> new LineResponse(l.sku(), l.cartons(), l.unitsPerCarton())).toList(),
                    p.status() == ProductionOrderStatus.SAMPLE_PENDING
                            ? view.rounds().waiting().map(SampleRound::id).orElse(null) : null,
                    view.rounds().rounds().stream().map(r -> new RoundResponse(r.id(), r.roundNo(), r.openedAt(),
                            r.physicalSampleSentOn(), r.result(), r.customerNote(), r.answeredAt(),
                            ProductionSampleRoundController.MediaResponse.of(r))).toList());
        }
    }

    private final ProductionSampleService samples;
    private final CustomerService customers;

    CustomerProductionSampleController(ProductionSampleService samples, CustomerService customers) {
        this.samples = samples;
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "The signed-in customer's print samples waiting for their answer, oldest first")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SAMPLES, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<SampleResponse>> awaiting(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(activeCustomer(user).map(id -> samples.awaiting(id).stream().map(SampleResponse::of)
                .toList()).orElse(List.of()));
    }

    @GetMapping("/{roundId}")
    @Operation(summary = "One of the signed-in customer's rounds, with its production order's other rounds - answered "
            + "or not")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SAMPLES, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<SampleResponse> one(@PathVariable UUID roundId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SampleResponse.of(samples.customerRound(requireActive(user, roundId), roundId)));
    }

    @GetMapping("/{roundId}/media/{mediaId}/view-url")
    @Operation(summary = "A short-lived link showing one photo or video of the customer's own round")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SAMPLES, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<ProductionSampleRoundController.LinkResponse>> view(
            @PathVariable UUID roundId, @PathVariable UUID mediaId, @AuthenticatedUser CurrentUser user) {
        var link = samples.customerMediaLink(requireActive(user, roundId), roundId, mediaId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new ProductionSampleRoundController.LinkResponse(link.url(), link.expiresAt())));
    }

    @PostMapping("/{roundId}/approval")
    @Operation(summary = "Approve the print sample of the round: mass printing starts. Once per round - 409 "
            + "SAMPLE_ROUND_ANSWERED; another customer's round is 404")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SAMPLES, action = Action.APPROVE, scope = DataScope.OWN)
    public ApiResponse<SampleResponse> approve(@PathVariable UUID roundId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SampleResponse.of(samples.answer(requireActive(user, roundId), user.userId(), roundId,
                SampleRound.Result.APPROVED, null)));
    }

    @PostMapping("/{roundId}/reprint-request")
    @Operation(summary = "Ask for the round's print sample to be printed again, saying what to change. Once per round - "
            + "409 SAMPLE_ROUND_ANSWERED; another customer's round is 404")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SAMPLES, action = Action.APPROVE, scope = DataScope.OWN)
    public ApiResponse<SampleResponse> requestReprint(@PathVariable UUID roundId,
                                                      @Valid @RequestBody ReprintRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(SampleResponse.of(samples.answer(requireActive(user, roundId), user.userId(), roundId,
                SampleRound.Result.REPRINT_REQUESTED, request.note())));
    }

    /** The caller's customer if its profile is ACTIVE (13 BR-03); a caller who is no customer at all is refused. */
    private Optional<UUID> activeCustomer(CurrentUser user) {
        CustomerSummary customer = customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return ACTIVE.equals(customer.status()) ? Optional.of(customer.customerId()) : Optional.empty();
    }

    private UUID requireActive(CurrentUser user, UUID roundId) {
        return activeCustomer(user).orElseThrow(() -> new BusinessException(ErrorCode.SAMPLE_ROUND_NOT_FOUND,
                "No print sample round " + roundId));
    }
}
