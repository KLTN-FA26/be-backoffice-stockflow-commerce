package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.ConversationSummaryResponse;
import com.stockflow.assistant.internal.controller.dto.DraftResponse;
import com.stockflow.assistant.internal.controller.dto.NextStepsResponse;
import com.stockflow.assistant.internal.controller.dto.PriceReviewResponse;
import com.stockflow.assistant.internal.controller.dto.ReplyDraftRequest;
import com.stockflow.assistant.internal.controller.dto.ReplyDraftResponse;
import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.ChatDrafts;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.OrderNextSteps;
import com.stockflow.assistant.internal.service.QuotePriceReview;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The assistant's features inside the Sales screens (contract §1): "why is this order not moving" on the order, a reply
 * draft and a summary in the staff chat, and the price review beside the quote price approval.
 *
 * <p><b>The screen's permission, the screen's scope.</b> Each handler carries the permission of the screen its button
 * sits on, and refuses a portal account first ({@link ScopeGuards#requireBackOffice}): the CUSTOMER role holds
 * {@code sales-orders:READ} and {@code chat-conversations:READ} for its own side. An order is found as the order list
 * finds it for the caller, so a document out of their reach is 404 as on the screen.</p>
 *
 * <p><b>Nothing is written.</b> A draft is returned for the person to edit and send through the chat's own action
 * (ADR-0015). The two model-backed handlers spend the questions' rate limit ({@link AssistantResources#QUESTIONS_BUCKET});
 * the deterministic ones have their own, and never call a model.</p>
 *
 * <p><b>Counted once answered.</b> Each handler counts its feature's use ({@link FeatureUse}, contract §5.2) after the
 * feature answered: a refusal, or a model that did not answer, is not a use.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantSalesController {

    private final OrderNextSteps nextSteps;
    private final ChatDrafts drafts;
    private final QuotePriceReview priceReview;
    private final AssistantTelemetry telemetry;

    AssistantSalesController(OrderNextSteps nextSteps, ChatDrafts drafts, QuotePriceReview priceReview,
                             AssistantTelemetry telemetry) {
        this.nextSteps = nextSteps;
        this.drafts = drafts;
        this.priceReview = priceReview;
        this.telemetry = telemetry;
    }

    @GetMapping("/orders/{orderNumber}/next-steps")
    @Operation(summary = "Why the order is not moving: each reason, who must act and what to do next",
            description = "Needs sales-orders:READ, the order screen's; the order is found as the order list finds it "
                    + "for the caller (a warehouse-limited caller finds only theirs), else 404. Deterministic: the "
                    + "reasons are the order module's own checks and the sentences come from the message bundle. "
                    + "notChecked names what the caller's permissions do not reach.")
    @RequiresPermission(resource = AssistantResources.SALES_ORDERS, action = Action.READ)
    @RateLimit(limit = AssistantResources.WORK_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.WORK_BUCKET)
    public ApiResponse<NextStepsResponse> nextSteps(@PathVariable String orderNumber,
                                                    @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        OrderNextSteps.NextSteps steps = nextSteps.of(orderNumber, user);
        telemetry.featureUsed(FeatureUse.NEXT_STEPS, user);
        return ApiResponse.ok(new NextStepsResponse(steps.orderNumber(), steps.status(), steps.statusLabel(),
                steps.blockers().stream().map(step -> new NextStepsResponse.Blocker(step.code(), step.waitingOn(),
                        step.title(), step.detail(), step.nextStep(), step.batchSeq(), step.amountDue(), step.href()))
                        .toList(),
                steps.notChecked(), steps.movingNormally(), steps.truncated()));
    }

    @PostMapping("/chat/conversations/{conversationId}/reply-draft")
    @Operation(summary = "A reply to the customer, drafted for the salesperson to edit and send",
            description = "Needs chat-conversations:READ, the staff chat's; a portal account is refused. Reads the "
                    + "conversation as the customer saw it and the customer's own orders, quotes and contracts it is "
                    + "about; never an internal note, a hold's reason, another customer or a colleague's name. Nothing "
                    + "is posted. unverifiedCodes lists codes and numbers no source holds. 503 ASSISTANT_UNAVAILABLE "
                    + "when no model answers.")
    @RequiresPermission(resource = AssistantResources.CHAT_CONVERSATIONS, action = Action.READ)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<ReplyDraftResponse> replyDraft(@PathVariable UUID conversationId,
                                                      @Valid @RequestBody(required = false) ReplyDraftRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ChatDrafts.ReplyDraft reply = drafts.reply(conversationId, request == null ? null : request.instruction(),
                user);
        telemetry.featureUsed(FeatureUse.REPLY_DRAFT, user);
        return ApiResponse.ok(new ReplyDraftResponse(DraftResponse.of(reply.draft()), reply.orderNumbers(),
                reply.language()));
    }

    @PostMapping("/chat/conversations/{conversationId}/summary")
    @Operation(summary = "A summary of the conversation for the colleague who takes it over",
            description = "Needs chat-conversations:READ, the staff chat's; a portal account is refused. Reads the "
                    + "conversation's recent messages, internal notes included (the summary is for staff), and the "
                    + "customer's documents it is about. 503 ASSISTANT_UNAVAILABLE when no model answers.")
    @RequiresPermission(resource = AssistantResources.CHAT_CONVERSATIONS, action = Action.READ)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<ConversationSummaryResponse> summary(@PathVariable UUID conversationId,
                                                            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ChatDrafts.Summary summary = drafts.summary(conversationId, user);
        telemetry.featureUsed(FeatureUse.CHAT_SUMMARY, user);
        return ApiResponse.ok(new ConversationSummaryResponse(DraftResponse.of(summary.summary()),
                summary.customerAsks(), summary.promisesMade(), summary.openQuestions(), summary.orderNumbers()));
    }

    @GetMapping("/quotes/{quoteId}/price-review")
    @Operation(summary = "The quote's prices beside the customer's price, the standard price and the last price sold",
            description = "Needs sales-quote-price-approvals:APPROVE, the price approval screen's (the resource has no "
                    + "READ). credit is filled only for a caller holding customer-credit-profiles:READ, else null. "
                    + "Deterministic; the quote's own price check still decides whether it needs approval.")
    @RequiresPermission(resource = AssistantResources.QUOTE_PRICE_APPROVALS, action = Action.APPROVE)
    @RateLimit(limit = AssistantResources.WORK_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.WORK_BUCKET)
    public ApiResponse<PriceReviewResponse> priceReview(@PathVariable UUID quoteId,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        QuotePriceReview.Review review = priceReview.of(quoteId, user);
        telemetry.featureUsed(FeatureUse.PRICE_REVIEW, user);
        QuotePriceReview.Credit credit = review.credit();
        return ApiResponse.ok(new PriceReviewResponse(review.quoteId(), review.quoteNumber(), review.quoteStatus(),
                new PriceReviewResponse.Customer(review.customerId(), review.customerName(), review.customerCode()),
                review.lines().stream().map(line -> new PriceReviewResponse.Line(line.lineNo(), line.sku(),
                        line.cartons(), line.unitsPerCarton(), line.quotedUnitPrice(), line.customerPrice(),
                        line.standardPrice(), line.lastSoldUnitPrice(), line.lastSoldOn(), line.lastSoldOrderNumber(),
                        line.deltaVsReference(), line.reference(), line.flags())).toList(),
                credit == null ? null : new PriceReviewResponse.Credit(credit.limit(), credit.outstanding(),
                        credit.overdue(), credit.available(), credit.oldestOverdueDueDate(), credit.currency()),
                review.flags()));
    }
}
