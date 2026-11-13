package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.service.ChatAgentView;
import com.stockflow.chat.internal.service.ChatAttachmentService;
import com.stockflow.chat.internal.service.ChatConversationView;
import com.stockflow.chat.internal.service.ChatCustomerOverview;
import com.stockflow.chat.internal.service.ChatLogEntryView;
import com.stockflow.chat.internal.service.ChatMessagePage;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.chat.internal.service.ChatReadMarkerView;
import com.stockflow.chat.internal.service.CustomerChatService;
import com.stockflow.chat.internal.service.CustomerOverviewService;
import com.stockflow.chat.internal.service.StaffChatService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The staff's chat in the back office (kltn-docs 16 §4), feature F-13: the queue, a conversation, answering, internal
 * notes, claiming and handing on, the moves of 16 §5, and the customer beside it.
 *
 * <p>The CUSTOMER role holds {@code chat-conversations} too, for its own side ({@link CustomerChatController}); every
 * handler here refuses a portal account (403) through the service before it reads or validates anything. Who may answer
 * and move a conversation - the agent in charge, or a holder of {@code chat-supervision:UPDATE} - is the aggregate's
 * rule, answered with {@code CHAT_NOT_ASSIGNEE}.</p>
 *
 * <p>Realtime: subscribe to {@code /topic/chat.inbox}, {@code /topic/chat.conversations.{id}} and
 * {@code /topic/chat.conversations.{id}.staff} (internal notes) over STOMP at {@code /ws}.</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Sales chat (staff)")
@PermissionResource(code = ChatResources.CONVERSATIONS, group = "Sales chat", label = "Chat conversations",
        route = "/chat", apiPath = "/api/v1/chat/conversations",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class StaffChatController {

    private final StaffChatService chat;
    private final ChatAttachmentService attachments;
    private final CustomerOverviewService overviews;

    StaffChatController(StaffChatService chat, ChatAttachmentService attachments, CustomerOverviewService overviews) {
        this.chat = chat;
        this.attachments = attachments;
        this.overviews = overviews;
    }

    @GetMapping("/conversations")
    @Operation(summary = "The queue: by status (default: every status but the ended ones), mine, unassigned, overdue "
            + "(a late first response or resolution), customer or document; the overdue first (16 BR-09), then the "
            + "most recent")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<PageResponse<ChatConversationView>> inbox(@AuthenticatedUser CurrentUser user,
            @RequestParam(required = false) Set<ConversationStatus> status,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) ContextKind contextKind, @RequestParam(required = false) UUID contextId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(chat.inbox(user, new StaffChatService.InboxQuery(status, mine, unassigned, overdue,
                customerId, CustomerChatService.ref(contextKind, contextId), page, size)));
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a conversation with a customer (optionally about a document of theirs), assigned to the "
            + "caller; optionally with a first message")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.CREATE)
    public ApiResponse<ChatConversationView> open(@AuthenticatedUser CurrentUser user,
                                                  @RequestBody ChatRequests.StaffOpenConversation request) {
        return ApiResponse.ok(chat.open(user, request.customerId(), request.subject(),
                CustomerChatService.ref(request.contextKind(), request.contextId()), request.text(),
                request.clientMessageId()));
    }

    @GetMapping("/conversations/{conversationId}")
    @Operation(summary = "A conversation: its contexts with their numbers and statuses, the agent, the clocks, the read "
            + "markers, and the moves open to the caller")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<ChatConversationView> get(@PathVariable UUID conversationId,
                                                 @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.get(user, conversationId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "Messages, internal notes included: the newest first before `before`, or the oldest first "
            + "after `since`")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<ChatMessagePage> messages(@PathVariable UUID conversationId,
            @RequestParam(required = false) Long before, @RequestParam(required = false) Long since,
            @RequestParam(required = false) Integer limit, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.messages(user, conversationId, before, since, limit));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Answer the customer (the agent in charge or a supervisor: else 409 CHAT_NOT_ASSIGNEE); the "
            + "first answer stops the first-response clock. With cannedResponseId, that saved reply is sent, its "
            + "variables filled in by the server (needs chat-canned-responses:READ)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-messages")
    public ApiResponse<ChatMessageView> reply(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
                                              @RequestBody ChatRequests.SendMessage request) {
        return ApiResponse.ok(chat.reply(user, conversationId, request.text(), request.clientMessageId(),
                request.contextId(), request.cannedResponseId()));
    }

    @PostMapping("/conversations/{conversationId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "An internal note, never shown to the customer (16 BR-07), with @mentions of staff")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-messages")
    public ApiResponse<ChatMessageView> note(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
                                             @RequestBody ChatRequests.InternalNote request) {
        return ApiResponse.ok(chat.note(user, conversationId, request.text(), request.mentions(),
                request.clientMessageId(), request.contextId()));
    }

    @PostMapping(path = "/conversations/{conversationId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send the customer a file (multipart part 'file'; JPEG, PNG, WebP or PDF, 20 MB)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "chat-uploads")
    public ApiResponse<ChatMessageView> attach(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) String caption,
            @RequestParam(required = false) String clientMessageId) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(attachments.sendAsStaff(user, conversationId, upload, caption, clientMessageId));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping("/conversations/{conversationId}/attachments/{attachmentId}/link")
    @Operation(summary = "A short-lived link to download a file of the conversation")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ResponseEntity<ApiResponse<DownloadLink>> link(@PathVariable UUID conversationId,
                                                          @PathVariable UUID attachmentId,
                                                          @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(attachments.linkForStaff(user, conversationId, attachmentId)));
    }

    @PostMapping("/conversations/{conversationId}/read")
    @Operation(summary = "Mark read up to seq (the newest when omitted)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatReadMarkerView> read(@PathVariable UUID conversationId, @AuthenticatedUser CurrentUser user,
                                                @RequestBody(required = false) ChatRequests.MarkRead request) {
        return ApiResponse.ok(chat.markRead(user, conversationId, request == null ? null : request.seq()));
    }

    @PostMapping("/conversations/{conversationId}/claim")
    @Operation(summary = "Take a conversation nobody has; 409 CHAT_ALREADY_ASSIGNED when someone does")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatConversationView> claim(@PathVariable UUID conversationId,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.claim(user, conversationId));
    }

    @PostMapping("/conversations/{conversationId}/assignment")
    @Operation(summary = "Give the conversation to an agent: a supervisor assigns one nobody has; the agent in charge or "
            + "a supervisor transfers it. 409 CHAT_AGENT_NOT_AVAILABLE for someone who does not answer customers")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatConversationView> assign(@PathVariable UUID conversationId,
                                                    @AuthenticatedUser CurrentUser user,
                                                    @RequestBody ChatRequests.Assign request) {
        return ApiResponse.ok(chat.assign(user, conversationId, request.agentId(), request.note()));
    }

    @PostMapping("/conversations/{conversationId}/status")
    @Operation(summary = "Move the conversation (16 §5): WAITING_ON_CUSTOMER, WAITING_ON_INTERNAL, ESCALATED (reason "
            + "required), RESOLVED, ACTIVE. 409 CHAT_TRANSITION_NOT_ALLOWED for a move 16 §5 does not have from here")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatConversationView> status(@PathVariable UUID conversationId,
                                                    @AuthenticatedUser CurrentUser user,
                                                    @RequestBody ChatRequests.ChangeStatus request) {
        return ApiResponse.ok(chat.changeStatus(user, conversationId, request.status(), request.reason()));
    }

    @PostMapping("/conversations/{conversationId}/contexts")
    @Operation(summary = "Attach a document of the conversation's customer (16 BR-02: 404 CHAT_CONTEXT_NOT_FOUND for "
            + "another customer's)")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.UPDATE)
    public ApiResponse<ChatConversationView> attachContext(@PathVariable UUID conversationId,
                                                           @AuthenticatedUser CurrentUser user,
                                                           @RequestBody ChatRequests.AttachContext request) {
        return ApiResponse.ok(chat.attachContext(user, conversationId,
                request.kind() == null || request.id() == null ? null
                        : CustomerChatService.ref(request.kind(), request.id())));
    }

    @GetMapping("/conversations/{conversationId}/events")
    @Operation(summary = "The conversation's log (16 BR-01): who did what, on which context, the oldest first")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<List<ChatLogEntryView>> events(@PathVariable UUID conversationId,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.log(user, conversationId));
    }

    @GetMapping("/agents")
    @Operation(summary = "The staff who answer customers, online first: the assign / transfer dialog")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<List<ChatAgentView>> agents(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(chat.agents(user));
    }

    @GetMapping("/customers/{customerId}/overview")
    @Operation(summary = "The customer beside the conversation: profile, recent orders, quotes, contracts, production "
            + "orders, open instalments, designs and conversations, each from its own module")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    public ApiResponse<ChatCustomerOverview> overview(@PathVariable UUID customerId,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(overviews.overview(user, customerId));
    }
}
