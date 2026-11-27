package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.ErrorExplanationResponse;
import com.stockflow.assistant.internal.service.AssistantTelemetry;
import com.stockflow.assistant.internal.service.ErrorExplanations;
import com.stockflow.assistant.internal.service.FeatureUse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Help when the system says no (contract §4): the link "Vì sao?" next to a business error message explains which rules
 * refused and what to do next.
 *
 * <p><b>It explains a code, not a record.</b> The caller sends the code they were refused with, nothing else: the
 * answer is the same for everyone holding the panel and reveals no data, so it needs only
 * {@code assistant-conversations:READ}, scope OWN, back office first like every assistant endpoint.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantHelpController {

    private final ErrorExplanations explanations;
    private final AssistantTelemetry telemetry;

    AssistantHelpController(ErrorExplanations explanations, AssistantTelemetry telemetry) {
        this.explanations = explanations;
        this.telemetry = telemetry;
    }

    @GetMapping("/error-explanations/{errorCode}")
    @Operation(summary = "Why a business refusal happened: the rules behind its error code and what to do next",
            description = "Needs assistant-conversations:READ. Deterministic: read from a curated map of the refusals "
                    + "people meet in daily work and the rules of the kltn-docs corpus the assistant indexes; no model "
                    + "is asked. message is in the caller's language (Accept-Language); rules and nextSteps are "
                    + "Vietnamese. A code the map does not hold answers its message with no rules and no next steps: "
                    + "show the message alone. 404 NOT_FOUND for a value that is no error code. 60 a minute per "
                    + "user, shared with the assistant's other deterministic reads.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    @RateLimit(limit = AssistantResources.READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.READS_BUCKET)
    public ApiResponse<ErrorExplanationResponse> explain(@AuthenticatedUser CurrentUser user,
                                                         @PathVariable String errorCode) {
        ScopeGuards.requireBackOffice(user);
        ErrorExplanations.Explanation explanation = explanations.explain(errorCode);
        telemetry.featureUsed(FeatureUse.ERROR_EXPLANATION, user);
        return ApiResponse.ok(new ErrorExplanationResponse(explanation.errorCode().name(), explanation.message(),
                explanation.rules().stream().map(rule -> new ErrorExplanationResponse.Rule(rule.ruleId(),
                        rule.chapter(), rule.title(), rule.text(), null)).toList(),
                explanation.nextSteps()));
    }
}
