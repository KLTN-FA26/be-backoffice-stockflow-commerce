package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.service.ChatCardActions;
import com.stockflow.chat.internal.service.ChatConversationView;
import com.stockflow.chat.internal.service.ChatMessageEdits;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.chat.internal.service.CustomerChatService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The customer's actions in a conversation beyond writing (kltn-docs 16 package 3): acting on a card - which calls the
 * document's own module (BR-03) - rating a resolved conversation, correcting or taking back their own message, and the
 * unread badge. Same rules as {@link CustomerChatController}: the customer comes from the sign-in, another customer's
 * conversation or message answers 404.
 */
@RestController
@RequestMapping("/api/v1/chat/me/conversations")
@Tag(name = "Sales chat (customer)")
class CustomerChatActionsController {

    private final CustomerChatService chat;
    private final ChatCardActions cards;
    private final ChatMessageEdits edits;

    CustomerChatActionsController(CustomerChatService chat, ChatCardActions cards, ChatMessageEdits edits) {
        this.chat = chat;
        this.cards = cards;
        this.edits = edits;
    }

    @PostMapping("/{conversationId}/cards/{messageId}/actions")
    @Operation(summary = "Act on a card: ACCEPT (version, cartons per quote line id) or REQUEST_CHANGES (note) a quote; "
            + "APPROVE or REQUEST_REPRINT (note, required) a print sample; RATE (score 1-5, comment) the survey. Calls "
            + "the document's own operation (16 BR-03): its refusals come back as they are (QUOTE_EXPIRED, "
            + "SAMPLE_ROUND_ANSWERED...). 409 CHAT_CARD_ACTION_NOT_ALLOWED for an action the card does not offer now")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 30, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-card-actions")
    public ApiResponse<ChatMessageView> act(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                            @AuthenticatedUser CurrentUser user,
                                            @RequestBody ChatRequests.CardAction request) {
        return ApiResponse.ok(cards.customerAct(user, conversationId, messageId, action(request)));
    }

    @PostMapping("/{conversationId}/csat")
    @Operation(summary = "Rate a resolved conversation, once: 1 to 5 stars and an optional comment. 409 "
            + "CHAT_CSAT_NOT_OPEN unless it is resolved, CHAT_CSAT_ALREADY_GIVEN the second time")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ChatConversationView> rate(@PathVariable UUID conversationId,
                                                  @AuthenticatedUser CurrentUser user,
                                                  @RequestBody ChatRequests.Rate request) {
        return ApiResponse.ok(chat.rate(user, conversationId, request.score(), request.comment()));
    }

    @PatchMapping("/{conversationId}/messages/{messageId}")
    @Operation(summary = "Correct one's own text within 5 minutes of sending it. 409 CHAT_EDIT_WINDOW_PASSED, "
            + "CHAT_MESSAGE_NOT_EDITABLE")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 60, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-messages")
    public ApiResponse<ChatMessageView> edit(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                             @AuthenticatedUser CurrentUser user,
                                             @RequestBody ChatRequests.EditMessage request) {
        return ApiResponse.ok(edits.customerEdit(user, conversationId, messageId, request.text()));
    }

    @DeleteMapping("/{conversationId}/messages/{messageId}")
    @Operation(summary = "Take back one's own text or file within 5 minutes of sending it; it shows as deleted")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ChatMessageView> delete(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(edits.customerDelete(user, conversationId, messageId));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "The badge: in how many of the customer's conversations, and how many messages, are unread")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<ChatMessageRepository.UnreadTotal> unread(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.unread(user));
    }

    static ChatCardActions.Action action(ChatRequests.CardAction request) {
        return request == null ? new ChatCardActions.Action(null, null, null, null, null, null)
                : new ChatCardActions.Action(request.action(), request.version(), request.cartons(), request.note(),
                request.score(), request.comment());
    }
}
