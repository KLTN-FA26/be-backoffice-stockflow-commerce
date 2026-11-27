package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.FeatureUsageResponse;
import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.service.FeatureUse;
import com.stockflow.common.api.ApiResponse;
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

import java.time.Clock;
import java.time.LocalDate;

/**
 * How often each assistant feature is used, per feature, role and day (contract §5.2): what the System Admin and the
 * Sales Manager look at to keep the features people use and drop the ones nobody does. Counts only; nothing says who.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
@PermissionResource(
        code = AssistantResources.USAGE,
        group = "Assistant",
        label = "AI assistant: feature usage",
        apiPath = "/api/v1/assistant/usage",
        actions = {Action.READ})
class AssistantUsageController {

    /** The period read when none is given: the last thirty days. */
    private static final int DEFAULT_DAYS = 30;

    private final FeatureUse featureUse;
    private final Clock clock;

    AssistantUsageController(FeatureUse featureUse, Clock clock) {
        this.featureUse = featureUse;
        this.clock = clock;
    }

    @GetMapping("/usage")
    @Operation(summary = "Assistant feature use per feature key, role and day",
            description = "Needs assistant-usage:READ (System Admin, Sales Manager). from and to are business days "
                    + "(Vietnam time), both included; the last 30 days by default, at most 92. A row is a day, a "
                    + "feature and a role with at least one use; a feature with no row was not used. Counts reach "
                    + "this endpoint up to a minute after the use. 400 VALIDATION_FAILED when to is before from or "
                    + "the period is longer than 92 days.")
    @RequiresPermission(resource = AssistantResources.USAGE, action = Action.READ, scope = DataScope.ALL)
    @RateLimit(limit = AssistantResources.READS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.READS_BUCKET)
    public ApiResponse<FeatureUsageResponse> usage(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ScopeGuards.requireBackOffice(user);
        LocalDate last = to == null ? BusinessDate.of(clock.instant()) : to;
        LocalDate first = from == null ? last.minusDays(DEFAULT_DAYS - 1L) : from;
        return ApiResponse.ok(new FeatureUsageResponse(first, last, FeatureUse.KEYS.stream().sorted().toList(),
                featureUse.between(first, last).stream().map(count -> new FeatureUsageResponse.Row(count.day(),
                        count.featureKey(), count.role(), count.uses())).toList()));
    }
}
