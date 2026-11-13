package com.stockflow.chat.internal.controller.dto;

import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ConversationStatus;

import java.util.List;
import java.util.UUID;

/**
 * The chat's request bodies.
 *
 * <p>Deliberately without bean-validation annotations: every chat endpoint first decides <i>who</i> may call it - a
 * staff endpoint refuses a portal account (403), a customer endpoint answers 404 for a conversation that is not theirs
 * - and only then <i>what</i> they sent. Bean validation would run before the handler and answer a customer probing a
 * staff endpoint with a 400 describing the body; the service validates instead (VALIDATION_FAILED) after the caller is
 * settled. {@code PermissionMatrixTest} probes exactly this.</p>
 */
public final class ChatRequests {

    private ChatRequests() {
    }

    /**
     * The customer opens a conversation from the page they are on.
     *
     * @param contextKind     with {@code contextId}: the document of the page (a product, their SKU, design, quote,
     *                        order, contract, production order or invoice); both null for "Chat with Sales"
     * @param text            the first message; may be null
     * @param clientMessageId the client's own id of that first message, so a retried open does not post it twice
     */
    public record OpenConversation(String subject, ContextKind contextKind, UUID contextId, String text,
                                   String clientMessageId) {
    }

    /** Sales open a conversation with a customer, about a document of theirs or none. */
    public record StaffOpenConversation(UUID customerId, String subject, ContextKind contextKind, UUID contextId,
                                        String text, String clientMessageId) {
    }

    /**
     * A text message.
     *
     * @param clientMessageId the client's own id of the message (letters, digits, {@code . _ : -}, up to 64): a retried
     *                        send with the same id is the same message. A UUID is a good choice.
     * @param contextId       staff only: the conversation's context the answer is about
     */
    public record SendMessage(String text, String clientMessageId, UUID contextId) {
    }

    /** An internal note, naming the staff it mentions. */
    public record InternalNote(String text, List<UUID> mentions, String clientMessageId, UUID contextId) {
    }

    /** Read up to this message's sequence; the newest when null. */
    public record MarkRead(Long seq) {
    }

    /** Give the conversation to this agent (assign one nobody has, or transfer). */
    public record Assign(UUID agentId, String note) {
    }

    /**
     * A move of the conversation: {@code WAITING_ON_CUSTOMER}, {@code WAITING_ON_INTERNAL}, {@code ESCALATED} (reason
     * required), {@code RESOLVED} or {@code ACTIVE}.
     */
    public record ChangeStatus(ConversationStatus status, String reason) {
    }

    /** A document of the conversation's customer to attach to it. */
    public record AttachContext(ContextKind kind, UUID id) {
    }

    /** A typing signal over the socket. */
    public record Typing(boolean typing) {
    }
}
