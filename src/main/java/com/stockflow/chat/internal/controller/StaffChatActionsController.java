package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.service.CannedResponses;
import com.stockflow.chat.internal.service.ChatCardActions;
import com.stockflow.chat.internal.service.ChatMessageEdits;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.chat.internal.service.ChatSearch;
import com.stockflow.chat.internal.service.StaffChatService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The staff's actions in a conversation beyond answering (kltn-docs 16 package 3): Sales' card actions (a quote drawn
 * up from a quote request card, BR-11), transfer details as the system's card (BR-06), editing their own message, the
 * search over what was said, the badge and a saved reply previewed in a conversation. Every one refuses a portal account
 * first ({@code ChatCallers.staff}).
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Sales chat (staff)")
class StaffChatActionsController {

    private final StaffChatService chat;
    private final ChatCardActions cards;
    private final ChatMessageEdits edits;
    private final ChatSearch search;
    private final CannedResponses canned;

    StaffChatActionsController(StaffChatService chat, ChatCardActions cards, ChatMessageEdits edits,
                               ChatSearch search, CannedResponses canned) {
        this.chat = chat;
        this.cards = cards;
        this.edits = edits;
        this.search = search;
        this.canned = canned;
    }

    @PostMapping("/conversations/{conversationId}/cards/{messageId}/actions")
    @Operation(summary = "Sales act on a card: CREATE_QUOTE on a quote request card draws up a DRAFT quote prefilled "
            + "with the customer's SKUs, the cartons asked and the delivery date (16 BR-11, 14 BR-22) - only once its "
            + "design is approved: 409 CHAT_DESIGN_NOT_APPROVED. The customer's actions (accept, approve a sample) are "
            + "409 CHAT_CARD_ACTION_NOT_ALLOWED here (16 BR-10)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatMessageView> act(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                            @AuthenticatedUser CurrentUser user,
                                            @RequestBody ChatRequests.CardAction request) {
        return ApiResponse.ok(cards.staffAct(user, conversationId, messageId,
                CustomerChatActionsController.action(request)));
    }

    @PostMapping("/conversations/{conversationId}/cards/transfer-details")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send the transfer details of one of the customer's installments as the system's card: the "
            + "company's account from configuration, the amount still owed, the transfer content and a VietQR payload "
            + "(16 BR-06 - an account number is never typed). reminder=true for an overdue one")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatMessageView> transferDetails(@PathVariable UUID conversationId,
                                                        @AuthenticatedUser CurrentUser user,
                                                        @RequestBody ChatRequests.TransferDetails request) {
        return ApiResponse.ok(cards.sendTransferDetails(user, conversationId, request.installmentId(),
                Boolean.TRUE.equals(request.reminder())));
    }

    @PatchMapping("/conversations/{conversationId}/messages/{messageId}")
    @Operation(summary = "Correct one's own text or note within 5 minutes of sending it")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-messages")
    public ApiResponse<ChatMessageView> edit(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                             @AuthenticatedUser CurrentUser user,
                                             @RequestBody ChatRequests.EditMessage request) {
        return ApiResponse.ok(edits.staffEdit(user, conversationId, messageId, request.text()));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
    @Operation(summary = "Take back one's own text, note or file within 5 minutes of sending it")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatMessageView> delete(@PathVariable UUID conversationId, @PathVariable UUID messageId,
                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(edits.staffDelete(user, conversationId, messageId));
    }

    @GetMapping("/search")
    @Operation(summary = "Search what was said, internal notes included: full text, accents ignored ('bao gia' finds "
            + "'báo giá'), and a word typed in part; of one customer or conversation when given; newest first")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    @RateLimit(limit = 30, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-search")
    public ApiResponse<PageResponse<ChatSearch.Hit>> search(@AuthenticatedUser CurrentUser user,
            @RequestParam String q, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID conversationId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(search.search(user, q, customerId, conversationId, page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "The agent's badge: unread in the conversations in their charge, and the queue waiting")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<StaffChatService.StaffUnread> unread(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.unread(user));
    }

    @GetMapping("/conversations/{conversationId}/canned-responses/{cannedResponseId}/preview")
    @Operation(summary = "A saved reply as it would be sent in this conversation, its variables filled in")
    @RequiresPermission(resource = ChatResources.CANNED_RESPONSES, action = Action.READ)
    public ApiResponse<CannedResponses.Rendered> preview(@PathVariable UUID conversationId,
                                                         @PathVariable UUID cannedResponseId,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(canned.preview(user, cannedResponseId, conversationId));
    }
}
