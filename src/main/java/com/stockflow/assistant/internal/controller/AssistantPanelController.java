package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.CapabilitiesResponse;
import com.stockflow.assistant.internal.controller.dto.TodayResponse;
import com.stockflow.assistant.internal.service.ScopeLimit;
import com.stockflow.assistant.internal.service.TodayCard;
import com.stockflow.assistant.internal.service.ToolAvailability;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the assistant panel shows before a question: which tools the caller may use, what is missing for the others,
 * how many of today's questions are left, and the "today" card of what wants attention in their work.
 *
 * <p><b>It reports; the tools decide.</b> The answer is read from the declarations every tool is enforced by
 * ({@link ToolAvailability}), so each role's panel offers exactly what it can ask with no copy of the rules on the
 * front end, and a grant changed in the role matrix shows at once. A tool reported as granted still checks the caller
 * when it runs, and the card's sections are such runs ({@link TodayCard}).</p>
 *
 * <p>A controller of its own because {@link AssistantController} is mapped at {@code /conversations}. Guarded the same
 * way, scope OWN and back office first: the answer is about the caller alone.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantPanelController {

    /**
     * The card's rate-limit bucket: its own, never the questions' {@code assistant-questions}. The card asks no model,
     * so it must not spend a question; and the limiter bounds a bucket by the capacity each call passes
     * ({@code RedisRateLimiter}), so one bucket under two limits would cut the questions' burst of ten down to the
     * card's one.
     */
    private static final String TODAY = "assistant-today";

    /**
     * Cards one user may open a minute. A card runs up to seven tools, the low-stock count reading every SKU with a
     * threshold, so reopening the panel must not repeat them; the panel keeps the last card and its time.
     */
    private static final int CARDS_PER_MINUTE = 1;

    private final ToolAvailability availability;
    private final TodayCard card;

    AssistantPanelController(ToolAvailability availability, TodayCard card) {
        this.availability = availability;
        this.card = card;
    }

    @GetMapping("/capabilities")
    @Operation(summary = "What the assistant can look up for the caller, tool by tool",
            description = "Needs assistant-conversations:READ. A tool is granted when the caller holds everything it "
                    + "requires and, for company-wide figures, is not limited to particular warehouses; otherwise "
                    + "missing lists what to grant. It only reports: every tool still checks the caller when it runs.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<CapabilitiesResponse> capabilities(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(availability.capabilitiesOf(user)));
    }

    @GetMapping("/today")
    @Operation(summary = "The caller's \"today\" card: what wants attention in their work, read now",
            description = "Needs assistant-conversations:READ. Each section runs one assistant tool under the caller's "
                    + "own permissions and warehouses, exactly as a question would, and appears only when the caller "
                    + "may use that tool: low stock, expired and expiring lots, held orders and orders awaiting "
                    + "payment, purchase orders a supplier is late with, overdue production orders. A section whose "
                    + "tool is refused or fails has that outcome, no table and a note; the others still answer. No "
                    + "model is asked and nothing is stored, so it does not count toward the daily question limit. "
                    + "One card a minute per user: a second is 429 RATE_LIMITED with Retry-After, so keep showing "
                    + "the last card with its generatedAt.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    @RateLimit(limit = CARDS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER, bucket = TODAY)
    public ApiResponse<TodayResponse> today(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(card.of(user)));
    }

    private static CapabilitiesResponse toResponse(ToolAvailability.Capabilities capabilities) {
        return new CapabilitiesResponse(capabilities.scope().name(), capabilities.warehouses(),
                capabilities.dailyLimit(), capabilities.askedToday(),
                capabilities.tools().stream().map(AssistantPanelController::toCapability).toList());
    }

    private static CapabilitiesResponse.ToolCapability toCapability(ToolAvailability.ToolAccess tool) {
        return new CapabilitiesResponse.ToolCapability(tool.name(), tool.granted(), codes(tool.requires()),
                codes(tool.missing()), tool.scopeLimit() == ScopeLimit.NONE ? null : tool.scopeLimit().name());
    }

    private static List<String> codes(List<ToolAvailability.Requirement> requirements) {
        return requirements.stream().map(ToolAvailability.Requirement::code).toList();
    }

    private static TodayResponse toResponse(TodayCard.Card card) {
        return new TodayResponse(card.generatedAt(),
                card.sections().stream().map(AssistantPanelController::toSection).toList());
    }

    /** A cell without a value is a JSON null node, which is written as null, so every row shows every column. */
    private static TodayResponse.Section toSection(TodayCard.Section section) {
        return new TodayResponse.Section(section.key(), section.tool(), section.outcome().name(),
                section.table() == null ? null : AssistantWebMapper.table(section.table()), section.note());
    }
}
