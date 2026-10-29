package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.CapabilitiesResponse;
import com.stockflow.assistant.internal.service.ScopeLimit;
import com.stockflow.assistant.internal.service.ToolAvailability;
import com.stockflow.common.api.ApiResponse;
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
 * What the assistant panel needs before a question: which tools the caller may use, what is missing for the others,
 * and how many of today's questions are left.
 *
 * <p><b>It reports; the tools decide.</b> The answer is read from the declarations every tool is enforced by
 * ({@link ToolAvailability}), so each role's panel offers exactly what it can ask with no copy of the rules on the
 * front end, and a grant changed in the role matrix shows at once. A tool reported as granted still checks the caller
 * when it runs.</p>
 *
 * <p>A controller of its own because {@link AssistantController} is mapped at {@code /conversations}. Guarded the same
 * way, scope OWN and back office first: the answer is about the caller alone.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
class AssistantPanelController {

    private final ToolAvailability availability;

    AssistantPanelController(ToolAvailability availability) {
        this.availability = availability;
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
}
