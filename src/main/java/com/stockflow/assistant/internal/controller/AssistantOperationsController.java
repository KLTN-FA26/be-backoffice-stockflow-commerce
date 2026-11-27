package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.CartonInvestigationResponse;
import com.stockflow.assistant.internal.controller.dto.DraftResponse;
import com.stockflow.assistant.internal.controller.dto.ProductionFollowUpsResponse;
import com.stockflow.assistant.internal.controller.dto.WorkQueueResponse;
import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.CartonInvestigation;
import com.stockflow.assistant.internal.service.Draft;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.ProductionFollowUps;
import com.stockflow.assistant.internal.service.WorkQueue;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The assistant's work features for the warehouse and production (contract 3.1-3.3): the work queue, a missing
 * carton investigated, and the Production Coordinator's follow-ups with a draft to the producer.
 *
 * <p><b>The screen's permission, the screen's scope</b> (contract §0 rule 2). The work queue is the caller's own
 * (scope OWN, like the panel's card), each of its sections read under the guard of the screen it counts. The carton
 * investigation carries the carton lookup's guard ({@code inventory-stock-packages:READ}) and the follow-ups the
 * production orders screen's ({@code production-orders:READ}), so their modules apply the warehouse rule exactly as for
 * those screens. The draft to a producer carries the guard of the production order's own actions with its producer
 * (send, acceptance, progress: {@code production-orders:UPDATE}): only the Production Coordinator writes to producers,
 * and a Sales or warehouse reader of the list has no message to send. Permission resources of other modules are named
 * by value, as the tools name them.</p>
 *
 * <p><b>Rate limits</b> (rule 6): the deterministic reads share a generous bucket of their own; the draft spends the
 * questions' bucket, under the questions' capacity, so a draft costs what a question costs.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantOperationsController {

    /** The deterministic work reads' bucket: never the questions', which they must not spend. */
    private static final String WORK_READS = "assistant-work-reads";

    /** Reads one user may make a minute: a dashboard reloaded, a few cartons looked for in a row. */
    private static final int WORK_READS_PER_MINUTE = 30;

    /** {@code InventoryResources.STOCK_PACKAGES}: the carton lookup. */
    private static final String STOCK_PACKAGES = "inventory-stock-packages";

    /** {@code OrderResources.PRODUCTION_ORDERS}: the production orders screen. */
    private static final String PRODUCTION_ORDERS = "production-orders";

    private final WorkQueue workQueue;
    private final CartonInvestigation cartons;
    private final ProductionFollowUps followUps;
    private final AssistantTelemetry telemetry;

    AssistantOperationsController(WorkQueue workQueue, CartonInvestigation cartons, ProductionFollowUps followUps,
                                  AssistantTelemetry telemetry) {
        this.workQueue = workQueue;
        this.cartons = cartons;
        this.followUps = followUps;
        this.telemetry = telemetry;
    }

    @GetMapping("/work-queue")
    @Operation(summary = "What waits on the caller today, section by section (contract 3.1)",
            description = "Needs assistant-conversations:READ. Each section appears only when the caller holds the "
                    + "permission of the screen it counts, read under that screen's data scope: a warehouse-bound "
                    + "caller's sections are their warehouses'. warehouse (a code prefix, any case) narrows the "
                    + "sections that belong to a warehouse to that one and leaves the others out; 404 "
                    + "WAREHOUSE_NOT_FOUND for no such active warehouse or one the caller is not assigned to. "
                    + "Deterministic: no model is asked. A section that fails has failed=true and count null; the "
                    + "others still answer.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    @RateLimit(limit = WORK_READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER, bucket = WORK_READS)
    public ApiResponse<WorkQueueResponse> workQueue(@AuthenticatedUser CurrentUser user,
                                                    @RequestParam(required = false) String warehouse) {
        ScopeGuards.requireBackOffice(user);
        WorkQueue.Queue queue = workQueue.of(user, warehouse);
        telemetry.featureUsed(FeatureUse.WORK_QUEUE, user);
        return ApiResponse.ok(new WorkQueueResponse(queue.generatedAt(), queue.warehouse(), queue.sections().stream()
                .map(section -> new WorkQueueResponse.Section(section.key(), section.title(), section.count(),
                        section.severity().name(), section.href(), section.items().stream()
                        .map(item -> new WorkQueueResponse.Item(item.label(), item.detail(), item.at(), item.href()))
                        .toList(), section.failed()))
                .toList()));
    }

    @GetMapping("/cartons/{lpn}/investigation")
    @Operation(summary = "Where a missing carton may be: its history, the open work naming it, where to look (3.2)",
            description = "Needs inventory-stock-packages:READ, the carton lookup's permission and scope. The timeline "
                    + "(stock ledger, newest first, at most 30) needs inventory-stock-movements:READ, else it is empty "
                    + "with timelineWithheld NO_PERMISSION; for a counter who may not see the books of a carton a "
                    + "count holds it is empty with BLIND_COUNT, and its pending adjustment is not listed. whereToLook "
                    + "is ranked by fixed rules. 404 STOCK_PACKAGE_NOT_FOUND; 403 OUT_OF_DATA_SCOPE for a carton of "
                    + "another warehouse, as the carton lookup answers. Deterministic.")
    @RequiresPermission(resource = STOCK_PACKAGES, action = Action.READ)
    @RateLimit(limit = WORK_READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER, bucket = WORK_READS)
    public ApiResponse<CartonInvestigationResponse> investigateCarton(@AuthenticatedUser CurrentUser user,
                                                                      @PathVariable String lpn) {
        ScopeGuards.requireBackOffice(user);
        CartonInvestigation.Investigation found = cartons.investigate(lpn);
        telemetry.featureUsed(FeatureUse.CARTON_INVESTIGATION, user);
        return ApiResponse.ok(new CartonInvestigationResponse(found.lpn(), found.sku(), found.status(),
                found.stockStatus(), found.locationCode(), found.warehouseCode(), found.lotNumber(),
                found.receivedAt(), found.timeline().stream().map(entry -> new CartonInvestigationResponse
                        .TimelineEntry(entry.at(), entry.movementType(), entry.from(), entry.to(), entry.reference(),
                        entry.by())).toList(), found.timelineTotal(), found.timelineWithheld(),
                found.openWork().stream().map(work -> new CartonInvestigationResponse.OpenWork(work.kind(),
                        work.reference(), work.status(), work.href())).toList(),
                found.whereToLook().stream().map(place -> new CartonInvestigationResponse.Place(place.locationCode(),
                        place.why(), place.detail())).toList()));
    }

    @GetMapping("/production-follow-ups")
    @Operation(summary = "Production orders to chase: late, at risk or due within 3 days, the most urgent first (3.3)",
            description = "Needs production-orders:READ, under the production orders screen's warehouse rule. Each "
                    + "names the delivery batch it holds up; the order number and the customer only for a caller who "
                    + "may read orders (sales-orders:READ). At most 50, total counting all. Deterministic.")
    @RequiresPermission(resource = PRODUCTION_ORDERS, action = Action.READ)
    @RateLimit(limit = WORK_READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER, bucket = WORK_READS)
    public ApiResponse<ProductionFollowUpsResponse> productionFollowUps(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ProductionFollowUps.FollowUpList list = followUps.list(user);
        telemetry.featureUsed(FeatureUse.PRODUCTION_FOLLOW_UPS, user);
        return ApiResponse.ok(new ProductionFollowUpsResponse(list.today(), list.items().stream()
                .map(item -> new ProductionFollowUpsResponse.Item(item.productionOrderId(), item.code(),
                        item.producer(), item.status(), item.reason(), item.expectedCompletion(), item.neededBy(),
                        item.daysLate(), item.cartons(), item.receivedCartons(), item.affects().stream()
                        .map(a -> new ProductionFollowUpsResponse.Affected(a.orderNumber(), a.batchSeq(),
                                a.customerName(), a.windowStart(), a.windowEnd())).toList(), item.priority(),
                        item.href()))
                .toList(), list.total(), list.truncated()));
    }

    @PostMapping("/production-follow-ups/{productionOrderId}/message-draft")
    @Operation(summary = "A draft message to the producer of one production order, to edit and send (3.3)",
            description = "Needs production-orders:UPDATE, as sending and following a production order with its "
                    + "producer does: the Production Coordinator's. Model-backed, from the production order's code, "
                    + "SKUs, cartons and dates only - never a customer, an order number or a price. unverifiedCodes "
                    + "lists "
                    + "codes and numbers the text states that no fact holds. Nothing is sent. 404 "
                    + "PRODUCTION_ORDER_NOT_FOUND (also for another warehouse's); 409 PRODUCTION_ORDER_STATUS_INVALID "
                    + "for one not with its producer; 503 ASSISTANT_UNAVAILABLE when no model answers; 429 "
                    + "RATE_LIMITED, sharing the questions' limit.")
    @RequiresPermission(resource = PRODUCTION_ORDERS, action = Action.UPDATE)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<ProductionFollowUpsResponse.MessageDraft> producerMessageDraft(
            @AuthenticatedUser CurrentUser user, @PathVariable UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        Draft draft = followUps.draft(productionOrderId);
        telemetry.featureUsed(FeatureUse.PRODUCER_MESSAGE_DRAFT, user);
        return ApiResponse.ok(new ProductionFollowUpsResponse.MessageDraft(DraftResponse.of(draft)));
    }
}
