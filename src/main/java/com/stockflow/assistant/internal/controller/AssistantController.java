package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.AnswerResponse;
import com.stockflow.assistant.internal.controller.dto.AskRequest;
import com.stockflow.assistant.internal.controller.dto.ConversationResponse;
import com.stockflow.assistant.internal.controller.dto.FeedbackRequest;
import com.stockflow.assistant.internal.controller.dto.TranscriptEntryResponse;
import com.stockflow.assistant.internal.service.AssistantService;
import com.stockflow.assistant.internal.service.AssistantStreams;
import com.stockflow.assistant.internal.service.ConversationService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * The assistant panel (ADR-0010): a user's own conversations, questions to the assistant, and their verdicts on its
 * answers.
 *
 * <p><b>The permission opens the panel and nothing else.</b> {@code assistant-conversations} grants no data: each tool
 * the assistant runs carries the permission of the screen showing the same data, so a user learns through the
 * assistant only what their own grants already let them read. Scope OWN everywhere: a conversation is one person's,
 * and another person's id is a 404.</p>
 *
 * <p><b>Back office only.</b> Every handler refuses a portal customer first, even one granted the permission by
 * mistake: the tools mirror staff screens, several of which rely on exactly this check.</p>
 *
 * <p><b>Two ways to ask, one budget.</b> The plain and the streamed endpoint share one rate-limit bucket: each has a
 * bucket of its own by default, and a client alternating them, or falling back from the stream to the plain one after
 * an error, would get twice the questions a minute the limit is meant to allow. Only the plain one honours an
 * {@code Idempotency-Key}; a stream can be neither held back nor replayed, so the streamed one refuses the header
 * instead of ignoring it.</p>
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

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final ConversationService conversations;
    private final AssistantService assistant;
    private final AssistantStreams streams;
    private final AssistantWebMapper mapper;

    AssistantController(ConversationService conversations, AssistantService assistant, AssistantStreams streams,
                        AssistantWebMapper mapper) {
        this.conversations = conversations;
        this.assistant = assistant;
        this.streams = streams;
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
            description = "Needs assistant-conversations:READ. 404 for a conversation that is not the caller's. Each "
                    + "answer carries its tables and unverified numbers as when it was given, the follow-up "
                    + "questions the caller may ask now, and the caller's verdict on it.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<List<TranscriptEntryResponse>> transcript(@AuthenticatedUser CurrentUser user,
                                                                 @PathVariable UUID conversationId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(mapper.toResponse(conversations.transcript(conversationId), user));
    }

    @PostMapping("/{conversationId}/messages")
    @Operation(summary = "Ask a question and get the answer",
            description = "Needs assistant-conversations:CREATE; what the answer may contain is decided by the "
                    + "caller's other permissions, tool by tool. Send an Idempotency-Key: a retry must not ask twice. "
                    + "10 questions a minute per user, shared with the streamed endpoint. 409 ASSISTANT_BUSY while a "
                    + "question in the same conversation is being answered; 429 ASSISTANT_DAILY_LIMIT, or "
                    + "ASSISTANT_QUOTA_EXHAUSTED when every model's quota is used up (do not retry until one "
                    + "is renewed); 503 ASSISTANT_UNAVAILABLE (retry), ASSISTANT_MISCONFIGURED or ASSISTANT_DISABLED "
                    + "(do not: an administrator must act).")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ApiResponse<AnswerResponse> ask(@AuthenticatedUser CurrentUser user, @PathVariable UUID conversationId,
                                           @Valid @RequestBody AskRequest request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(answered(assistant.ask(conversationId, request.text(), user), user));
    }

    @PostMapping(path = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a question and receive the answer as server-sent events",
            description = "Needs assistant-conversations:CREATE; the rate limit is shared with the non-streamed "
                    + "endpoint. Events: 'tool' ({tool, outcome}) as each tool finishes, then 'answer' (the same "
                    + "body as the non-streamed endpoint's data) or 'error' ({errorCode, message}). Refusals found "
                    + "before the stream starts are ordinary JSON responses: validation 400 (an Idempotency-Key "
                    + "too: a stream is never stored or replayed, so use the non-streamed endpoint for a retry that "
                    + "must not ask twice), 404, 409 ASSISTANT_BUSY, 429 ASSISTANT_DAILY_LIMIT, 503 "
                    + "ASSISTANT_DISABLED. A failure while answering is an event: ASSISTANT_UNAVAILABLE (retry), "
                    + "ASSISTANT_QUOTA_EXHAUSTED or ASSISTANT_MISCONFIGURED (do not).")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public SseEmitter askStreaming(@AuthenticatedUser CurrentUser user, @PathVariable UUID conversationId,
                                   @Parameter(hidden = true)
                                   @RequestHeader(name = IdempotencyKeys.HEADER, required = false)
                                   String idempotencyKey,
                                   @Valid @RequestBody AskRequest request) {
        ScopeGuards.requireBackOffice(user);
        if (idempotencyKey != null) {
            // Ignoring it would let a client believe its retry is safe; the filter lets streams through untouched.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, IdempotencyKeys.HEADER + " is not accepted on a "
                    + "streamed answer, which is never stored or replayed; send it to POST .../messages instead");
        }
        assistant.refuseEarly(conversationId, request.text(), user);
        return streams.ask(conversationId, request.text(), user, answer -> answered(answer, user));
    }

    @PostMapping("/{conversationId}/messages/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Give an answer a verdict: up or down, with a reason if wanted",
            description = "Needs assistant-conversations:CREATE. Only an answer in the caller's own conversation; 404 "
                    + "for any other message id, and for another person's conversation. A second verdict on the same "
                    + "answer replaces the first; the transcript shows the latest.")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    public void feedback(@AuthenticatedUser CurrentUser user, @PathVariable UUID conversationId,
                         @PathVariable UUID messageId, @Valid @RequestBody FeedbackRequest request) {
        ScopeGuards.requireBackOffice(user);
        conversations.rateAnswer(conversationId, messageId, user, mapper.toFeedback(request));
    }

    /**
     * The answer's body for {@code user}, on the request thread or the stream's worker alike. An answer stating numbers
     * that nothing it was given holds is logged with the correlation id, which MDC carries on both: the count and the
     * message, never the figures themselves, which are business data.
     */
    private AnswerResponse answered(AssistantService.Answer answer, CurrentUser user) {
        if (!answer.unverifiedNumbers().isEmpty()) {
            log.warn("Assistant answer {} states {} number(s) that no lookup, question or context holds "
                            + "(correlation id {})", answer.messageId(), answer.unverifiedNumbers().size(),
                    MDC.get(CorrelationIdFilter.MDC_KEY));
        }
        return mapper.toResponse(answer, user);
    }
}
