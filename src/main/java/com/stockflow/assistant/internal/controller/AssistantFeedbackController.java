package com.stockflow.assistant.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.controller.dto.MarkedAnswerResponse;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.MarkedAnswer;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.assistant.internal.service.FeedbackReview;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The answers people marked wrong, for the System Admin to review and turn into golden-set cases (contract §5.1).
 *
 * <p><b>Other people's questions.</b> Everything here was written by someone else: their question, which can name a
 * customer, their comment, the answer they were given. Hence a resource of its own, {@code assistant-feedback}, granted
 * to SYSTEM_ADMIN alone (AQ-16), never folded into the panel's {@code assistant-conversations}, which only ever shows a
 * person their own. Who rated is shown by role and warehouses, never by name.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
@PermissionResource(
        code = AssistantResources.FEEDBACK,
        group = "Assistant",
        label = "AI assistant: answers marked wrong",
        apiPath = "/api/v1/assistant/feedback",
        actions = {Action.READ})
class AssistantFeedbackController {

    private final FeedbackReview review;
    private final ObjectMapper json;

    AssistantFeedbackController(FeedbackReview review, ObjectMapper json) {
        this.review = review;
        this.json = json;
    }

    @GetMapping("/feedback")
    @Operation(summary = "Answers people gave a verdict, latest first, each with a golden-set case to complete",
            description = "Needs assistant-feedback:READ (System Admin). verdict defaults to DOWN; from and to are "
                    + "business days (Vietnam time), both included, by when the verdict was last given; the last 30 "
                    + "days by default. Each item: the question, the answer, every tool call with its arguments and "
                    + "outcome, the rater's comment and roles, and goldenCase, the shape of "
                    + "src/test/resources/assistant/golden-set.json to paste (useCase TODO, facts empty). Text as "
                    + "stored: customers appear by the aliases the model saw (KH-1). A verdict goes with its "
                    + "conversation when the retention job deletes it. Page size at most 50.")
    @RequiresPermission(resource = AssistantResources.FEEDBACK, action = Action.READ, scope = DataScope.ALL)
    @RateLimit(limit = AssistantResources.READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.READS_BUCKET)
    public ApiResponse<PageResponse<MarkedAnswerResponse>> marked(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "DOWN") FeedbackVerdict verdict,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(review.marked(verdict, from, to, page, size).map(this::toResponse));
    }

    private MarkedAnswerResponse toResponse(FeedbackReview.Review reviewed) {
        MarkedAnswer answer = reviewed.answer();
        MarkedAnswer.Rater rater = answer.rater();
        return new MarkedAnswerResponse(answer.feedbackId(), answer.messageId(), answer.verdict().name(),
                answer.comment(), answer.givenAt(),
                rater == null ? List.of() : FeatureUse.roles(rater.roles()),
                rater == null ? List.of() : rater.warehouses(),
                answer.question(), answer.answer(), answer.answeredAt(), answer.model(),
                answer.tools().stream().map(tool -> new MarkedAnswerResponse.ToolCall(tool.step(), tool.tool(),
                        arguments(tool.arguments()), tool.outcome().name())).toList(),
                reviewed.goldenCase());
    }

    private JsonNode arguments(String text) {
        try {
            return text == null ? json.createObjectNode() : json.readTree(text);
        } catch (JsonProcessingException unreadable) {
            return json.getNodeFactory().textNode(text);
        }
    }
}
