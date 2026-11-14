package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.service.ChatAttachmentService;
import com.stockflow.chat.internal.service.ChatConversationView;
import com.stockflow.chat.internal.service.ChatMessagePage;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.chat.internal.service.ChatReadMarkerView;
import com.stockflow.chat.internal.service.CustomerChatService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The customer's chat on the portal (kltn-docs 16 §4), feature F-13.
 *
 * <p><b>The customer is resolved from the sign-in, never from the request</b>: there is no customer id to pass, and a
 * conversation id of another customer answers {@code CHAT_CONVERSATION_NOT_FOUND} as one that does not exist. Every
 * message read here is read for the customer, so internal notes never reach this controller (16 BR-07, filtered in the
 * repository). The permission ({@code chat-conversations}) is the staff's too, with scope OWN here; the staff's
 * endpoints ({@link StaffChatController}) refuse a portal account.</p>
 *
 * <p>Realtime: subscribe to {@code /topic/chat.conversations.{id}} and {@code /user/queue/chat} over STOMP at
 * {@code /ws}; after the socket was lost, catch up with {@code GET .../messages?since=<last seq>}.</p>
 */
@RestController
@RequestMapping("/api/v1/chat/me/conversations")
@Tag(name = "Sales chat (customer)")
class CustomerChatController {

    private final CustomerChatService chat;
    private final ChatAttachmentService attachments;

    CustomerChatController(CustomerChatService chat, ChatAttachmentService attachments) {
        this.chat = chat;
        this.attachments = attachments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a conversation from the page the customer is on (optionally about a document of theirs: "
            + "contextKind + contextId), or reuse their open one about the same document; optionally with a first "
            + "message. 404 CHAT_CONTEXT_NOT_FOUND for a document that is not theirs (16 BR-02)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = 30, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "chat-open")
    public ApiResponse<ChatConversationView> open(@AuthenticatedUser CurrentUser user,
                                                  @RequestBody ChatRequests.OpenConversation request) {
        return ApiResponse.ok(chat.open(user, new CustomerChatService.Open(request.subject(),
                CustomerChatService.ref(request.contextKind(), request.contextId()), request.text(),
                request.clientMessageId())));
    }

    @GetMapping
    @Operation(summary = "The signed-in customer's conversations, closed ones included, the most recently active first; "
            + "with contextKind + contextId, only those about that document (an order's, quote's or contract's page)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<ChatConversationView>> mine(@AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) ContextKind contextKind, @RequestParam(required = false) UUID contextId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(chat.mine(user, CustomerChatService.ref(contextKind, contextId), page, size));
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "One of the customer's conversations, with what it is about and the read markers")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<ChatConversationView> get(@PathVariable UUID conversationId,
                                                 @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.get(user, conversationId));
    }

    @GetMapping("/{conversationId}/messages")
    @Operation(summary = "Messages, never an internal note: the newest first before `before` (cursor = seq), or, with "
            + "`since`, the oldest first after it - what a client that lost its socket missed")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<ChatMessagePage> messages(@PathVariable UUID conversationId,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long since,
            @RequestParam(required = false) Integer limit, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.messages(user, conversationId, before, since, limit));
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a text; a retry with the same clientMessageId (or Idempotency-Key) is the same message. "
            + "409 CHAT_CONVERSATION_ENDED past the reopen window: POST .../reopen starts its sequel")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 60, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-messages")
    public ApiResponse<ChatMessageView> send(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
                                             @RequestBody ChatRequests.SendMessage request) {
        return ApiResponse.ok(chat.send(user, conversationId, request.text(), request.clientMessageId()));
    }

    @PostMapping(path = "/{conversationId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a file (multipart part 'file'; JPEG, PNG, WebP or PDF, 20 MB), virus-scanned before it "
            + "is kept; optional caption and clientMessageId")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    @RateLimit(limit = 60, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "chat-uploads")
    public ApiResponse<ChatMessageView> attach(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String caption,
            @RequestParam(required = false) String clientMessageId) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(attachments.sendAsCustomer(user, conversationId, upload, caption, clientMessageId));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping("/{conversationId}/attachments/{attachmentId}/link")
    @Operation(summary = "A short-lived link to download a file of the conversation")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ResponseEntity<ApiResponse<DownloadLink>> link(@PathVariable UUID conversationId,
                                                          @PathVariable UUID attachmentId,
                                                          @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(attachments.linkForCustomer(user, conversationId, attachmentId)));
    }

    @PostMapping("/{conversationId}/read")
    @Operation(summary = "Mark read up to seq (the newest when omitted); a marker never moves back")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ChatReadMarkerView> read(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
                                                @RequestBody(required = false) ChatRequests.MarkRead request) {
        return ApiResponse.ok(chat.markRead(user, conversationId, request == null ? null : request.seq()));
    }

    @PostMapping("/{conversationId}/reopen")
    @Operation(summary = "Come back to a conversation: an open one is returned as it is, a resolved or auto-closed one "
            + "within its reopen window is REOPENED, a closed one (or past the window) gets a new conversation that "
            + "continues it (continuedFromId) - the one returned")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<ChatConversationView> reopen(@PathVariable UUID conversationId,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.reopen(user, conversationId));
    }
}
