package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadDisposition;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Files in a conversation (16 §3: reference pictures, photos of a faulty product, a signed contract): images and PDF up
 * to 20 MB ({@link FileCategory#CHAT_ATTACHMENT}), virus-scanned and stored by common storage before anything is
 * locked, then attached in a short transaction - the {@link PreparedFiles} pattern, so a slow scan never holds the
 * conversation's row lock.
 *
 * <p>Who may send is checked <b>before</b> the file is read: a file for another customer's conversation is refused
 * with the same 404 as one for no conversation, and never scanned or stored. A file is downloaded through a short-lived
 * signed link, asked for per file, and only by someone who may see its message.</p>
 */
@Service
public class ChatAttachmentService {

    private static final Duration LINK_VALID_FOR = Duration.ofMinutes(10);

    private final ChatCallers callers;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ConversationWriter writer;
    private final ChatViews views;
    private final FileTransfers files;
    private final FileStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ChatAttachmentService(ChatCallers callers, ConversationRepository conversations,
                                 ChatMessageRepository messages, ConversationWriter writer, ChatViews views,
                                 FileTransfers files, FileStorage storage, TransactionTemplate tx, Clock clock) {
        this.callers = callers;
        this.conversations = conversations;
        this.messages = messages;
        this.writer = writer;
        this.views = views;
        this.files = files;
        this.storage = storage;
        this.tx = tx;
        this.clock = clock;
    }

    /** A file from the customer, into their own conversation; it reopens or wakes it as a text would (16 §5). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatMessageView sendAsCustomer(CurrentUser user, UUID conversationId, FileUpload upload, String caption,
                                          String clientMessageId) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        tx.executeWithoutResult(s -> {
            Conversation c = conversations.findById(conversationId)
                    .filter(found -> found.customerId().equals(caller.customerId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
            if (c.status() == ConversationStatus.CLOSED || (c.status().isEnded() && !c.reopensAt(clock.instant()))) {
                throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
            }
        });
        return send(conversationId, caller.customerId(), caller.actor(), upload, caption, clientMessageId,
                c -> c.acceptCustomerMessage(caller.actor(), clock.instant(), writer.firstResponseDue(clock.instant())));
    }

    /** A file from the agent in charge (or a supervisor) to the customer. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatMessageView sendAsStaff(CurrentUser user, UUID conversationId, FileUpload upload, String caption,
                                       String clientMessageId) {
        Actor staff = callers.staff(user);
        user.require(ChatCallers.CONVERSATIONS, Action.UPDATE);
        tx.executeWithoutResult(s -> {
            Conversation c = conversations.findById(conversationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
            if (c.status().isEnded()) {
                throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
            }
            if (!staff.supervisor() && !user.userId().equals(c.assignedAgentId())) {
                throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE);
            }
        });
        return send(conversationId, null, staff, upload, caption, clientMessageId,
                c -> c.acceptAgentMessage(staff, clock.instant()));
    }

    @Transactional(readOnly = true)
    public DownloadLink linkForCustomer(CurrentUser user, UUID conversationId, UUID attachmentId) {
        UUID customerId = callers.customer(user).customerId();
        conversations.findById(conversationId).filter(c -> c.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
        return link(conversationId, attachmentId, Audience.CUSTOMER);
    }

    @Transactional(readOnly = true)
    public DownloadLink linkForStaff(CurrentUser user, UUID conversationId, UUID attachmentId) {
        callers.staff(user);
        return link(conversationId, attachmentId, Audience.STAFF);
    }

    private ChatMessageView send(UUID conversationId, UUID customerId, Actor sender, FileUpload upload,
                                 String caption, String clientMessageId, ToLongFunction<Conversation> accept) {
        try (PreparedFiles prepared = files.prepare(FileCategory.CHAT_ATTACHMENT, upload)) {
            return tx.execute(status -> {
                Conversation c = customerId == null ? writer.lock(conversationId)
                        : writer.lockOwn(conversationId, customerId);
                PreparedFiles.PreparedFile file = prepared.single();
                ChatMessage.Attachment attachment = new ChatMessage.Attachment(Identifiers.newId(),
                        file.file().key(), file.file().originalName(), file.file().contentType(),
                        file.file().sizeBytes(), file.sha256(), null, null, sender.userId());
                ConversationWriter.Posted posted = writer.post(c, sender, clientMessageId, caption, accept,
                        seq -> ChatMessage.attachment(Identifiers.newId(), c.id(), seq, sender, caption,
                                CustomerChatService.blankToNull(clientMessageId), List.of(attachment),
                                clock.instant()));
                if (!posted.replayed()) {
                    prepared.adopt();
                }
                return views.message(posted.message());
            });
        }
    }

    private DownloadLink link(UUID conversationId, UUID attachmentId, Audience audience) {
        ChatMessage message = messages.findByAttachment(attachmentId, audience)
                .filter(m -> m.conversationId().equals(conversationId) && m.deletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        ChatMessage.Attachment file = message.attachments().stream().filter(a -> a.id().equals(attachmentId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        String name = file.originalName() == null ? "attachment" : file.originalName();
        return new DownloadLink(storage.presignedDownloadUrl(file.fileKey(), LINK_VALID_FOR,
                DownloadDisposition.attachment(name)), clock.instant().plus(LINK_VALID_FOR));
    }
}
