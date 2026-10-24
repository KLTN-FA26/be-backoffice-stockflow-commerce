package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AnswerResponse;
import com.stockflow.assistant.internal.controller.dto.AskRequest;
import com.stockflow.assistant.internal.controller.dto.ConversationResponse;
import com.stockflow.assistant.internal.controller.dto.TranscriptEntryResponse;
import com.stockflow.assistant.internal.service.AssistantService;
import com.stockflow.assistant.internal.service.ConversationService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
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
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The assistant panel (ADR-0010): a user's own conversations, and questions to the assistant.
 *
 * <p><b>The permission opens the panel and nothing else.</b> {@code assistant-conversations} grants no data: each tool
 * the assistant runs carries the permission of the screen showing the same data, so a user learns through the
 * assistant only what their own grants already let them read. Scope OWN everywhere: a conversation is one person's,
 * and another person's id is a 404.</p>
 *
 * <p><b>Back office only.</b> Every handler refuses a portal customer first, even one granted the permission by
 * mistake: the tools mirror staff screens, several of which rely on exactly this check.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant/conversations")
@Tag(name = "Assistant", description = "The back-office AI assistant: answers staff questions about stock, orders, "
        + "purchasing and sales from live data, under the asker's own permissions. Reads only.")
@PermissionResource(
        code = AssistantResources.CONVERSATIONS,
        group = "Assistant",
        label = "AI assistant",
        route = "/assistant",
        apiPath = "/api/v1/assistant",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE})
class AssistantController {

    private final ConversationService conversations;
    private final AssistantService assistant;
    private final AssistantWebMapper mapper;

    AssistantController(ConversationService conversations, AssistantService assistant, AssistantWebMapper mapper) {
        this.conversations = conversations;
        this.assistant = assistant;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start an empty conversation",
            description = "Needs assistant-conversations:CREATE. The title is set by the first question.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ConversationResponse> start(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(mapper.toResponse(conversations.start(user)));
    }

    @GetMapping
    @Operation(summary = "The caller's conversations, most recently active first",
            description = "Needs assistant-conversations:READ. Only the caller's own.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<ConversationResponse>> mine(
            @AuthenticatedUser CurrentUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(conversations.mine(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(mapper::toResponse));
    }

    @GetMapping("/{conversationId}/messages")
    @Operation(summary = "A conversation's questions and answers, oldest first, each answer with the tools behind it",
            description = "Needs assistant-conversations:READ. 404 for a conversation that is not the caller's.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<TranscriptEntryResponse>> transcript(@AuthenticatedUser CurrentUser user,
                                                                 @PathVariable UUID conversationId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(conversations.transcript(conversationId).stream().map(mapper::toResponse).toList());
    }

    @PostMapping("/{conversationId}/messages")
    @Operation(summary = "Ask a question and get the answer",
            description = "Needs assistant-conversations:CREATE; what the answer may contain is decided by the "
                    + "caller's other permissions, tool by tool. Send an Idempotency-Key: a retry must not ask twice. "
                    + "409 ASSISTANT_BUSY while a question in the same conversation is being answered; 429 "
                    + "ASSISTANT_DAILY_LIMIT; 503 ASSISTANT_UNAVAILABLE (retry) or ASSISTANT_DISABLED (do not).")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = 10, perSeconds = 60, key = RateLimit.Key.USER)
    public ApiResponse<AnswerResponse> ask(@AuthenticatedUser CurrentUser user, @PathVariable UUID conversationId,
                                           @Valid @RequestBody AskRequest request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(mapper.toResponse(assistant.ask(conversationId, request.text(), user)));
    }
}
