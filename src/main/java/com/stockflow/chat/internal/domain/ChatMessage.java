package com.stockflow.chat.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One message of a conversation: who said what, to whom, in which place. Born through a factory per kind, each holding
 * the rule {@code ck_message_*} states again in the database - above all that an internal note is the staff's alone
 * (16 BR-07) and that a customer writes text and sends files, nothing else.
 *
 * <p>Not part of the {@link Conversation} aggregate: a conversation can hold thousands of messages, and none of its
 * rules needs them loaded. What ties a message to its conversation is the sequence number the conversation hands out
 * under its row lock ({@link Conversation#acceptCustomerMessage} and friends).</p>
 *
 * @param seq             the message's place in the conversation, from 1 with no gaps
 * @param clientMessageId the sending client's own id: a retried send with the same id is this message
 * @param contextId       the context (of the same conversation) the message is about; null when none in particular
 * @param card            the card, for a {@code CARD}; null otherwise
 * @param mentions        the staff an internal note mentions; empty otherwise
 */
public record ChatMessage(UUID id, UUID conversationId, long seq, MessageKind kind, Visibility visibility,
                          SenderType senderType, UUID senderId, String body, String clientMessageId, UUID contextId,
                          Card card, Instant sentAt, Instant editedAt, Instant deletedAt, UUID deletedBy,
                          Set<UUID> mentions, List<Attachment> attachments) {

    /** The longest message body ({@code chat.message.body}). */
    public static final int MAX_BODY = 4000;

    /**
     * A card's data. The payload is a JSON object, kept as text here so the domain needs no JSON library; the service
     * parses and checks it.
     */
    public record Card(CardKind kind, String payloadJson, CardState state, Instant updatedAt) {
        public Card {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payloadJson, "payloadJson");
            Objects.requireNonNull(state, "state");
        }
    }

    /** A stored file of a message (common storage, scanned before it was stored). */
    public record Attachment(UUID id, String fileKey, String originalName, String contentType, long sizeBytes,
                             String sha256, Integer width, Integer height, UUID uploadedBy) {
        public Attachment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fileKey, "fileKey");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(uploadedBy, "uploadedBy");
        }
    }

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(senderType, "senderType");
        Objects.requireNonNull(sentAt, "sentAt");
        mentions = mentions == null ? Set.of() : Set.copyOf(mentions);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if (seq < 1) {
            throw new IllegalArgumentException("A message has a place in its conversation, from 1");
        }
        if (kind == MessageKind.INTERNAL_NOTE && (visibility != Visibility.STAFF || senderType != SenderType.AGENT)) {
            throw new IllegalArgumentException("An internal note is written by the staff for the staff (16 BR-07)");
        }
        if (visibility == Visibility.STAFF && kind != MessageKind.INTERNAL_NOTE && kind != MessageKind.SYSTEM) {
            throw new IllegalArgumentException("Only a note or a system line is hidden from the customer");
        }
        if (senderType == SenderType.CUSTOMER && kind != MessageKind.TEXT && kind != MessageKind.ATTACHMENT) {
            throw new IllegalArgumentException("A customer writes text and sends files, nothing else");
        }
        if (kind == MessageKind.SYSTEM && senderType != SenderType.SYSTEM) {
            throw new IllegalArgumentException("A system line is the system's");
        }
        if (senderType != SenderType.SYSTEM && senderId == null) {
            throw new IllegalArgumentException("A person's message names who sent it");
        }
        if ((kind == MessageKind.CARD) != (card != null)) {
            throw new IllegalArgumentException("A card message has a card, and only a card message");
        }
        if (!mentions.isEmpty() && kind != MessageKind.INTERNAL_NOTE) {
            throw new IllegalArgumentException("Only an internal note mentions anyone");
        }
        if (!attachments.isEmpty() && kind != MessageKind.ATTACHMENT && kind != MessageKind.INTERNAL_NOTE) {
            throw new IllegalArgumentException("Files go with an attachment message or a note");
        }
    }

    /** A text from the customer or an agent, seen by both sides. */
    public static ChatMessage text(UUID id, UUID conversationId, long seq, Actor sender, String body,
                                   String clientMessageId, UUID contextId, Instant now) {
        requirePerson(sender);
        return new ChatMessage(id, conversationId, seq, MessageKind.TEXT, Visibility.CUSTOMER, sender.type(),
                sender.userId(), written(body), clientMessageId, contextId, null, now, null, null, null, Set.of(),
                List.of());
    }

    /** The staff's note, never shown to the customer (16 BR-07), naming the colleagues it mentions. */
    public static ChatMessage internalNote(UUID id, UUID conversationId, long seq, Actor author, String body,
                                           Set<UUID> mentions, String clientMessageId, UUID contextId, Instant now) {
        if (!author.isStaff()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the staff write internal notes");
        }
        return new ChatMessage(id, conversationId, seq, MessageKind.INTERNAL_NOTE, Visibility.STAFF, SenderType.AGENT,
                author.userId(), written(body), clientMessageId, contextId, null, now, null, null, null, mentions,
                List.of());
    }

    /** Files from the customer or an agent, seen by both sides, with an optional caption. */
    public static ChatMessage attachment(UUID id, UUID conversationId, long seq, Actor sender, String caption,
                                         String clientMessageId, List<Attachment> files, Instant now) {
        requirePerson(sender);
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An attachment message carries a file");
        }
        String text = caption == null || caption.isBlank() ? null : written(caption);
        return new ChatMessage(id, conversationId, seq, MessageKind.ATTACHMENT, Visibility.CUSTOMER, sender.type(),
                sender.userId(), text, clientMessageId, null, null, now, null, null, null, Set.of(), files);
    }

    /**
     * A card drawn from a document (package 3). Sent by the system, or by an agent's action that the system turns into
     * the card (16 BR-06, BR-10, BR-11: never typed by hand); always seen by both sides.
     *
     * @param fallbackText what a client that cannot draw the card shows instead; may be null
     */
    public static ChatMessage card(UUID id, UUID conversationId, long seq, Actor sender, CardKind kind,
                                   String payloadJson, UUID contextId, String fallbackText, Instant now) {
        if (sender.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A customer does not send cards");
        }
        String text = fallbackText == null || fallbackText.isBlank() ? null : requireText(fallbackText);
        return new ChatMessage(id, conversationId, seq, MessageKind.CARD, Visibility.CUSTOMER, sender.type(),
                sender.userId(), text, null, contextId, new Card(kind, payloadJson, CardState.OPEN, null), now, null,
                null, null, Set.of(), List.of());
    }

    /** A line the system writes, for both sides or for the staff alone. */
    public static ChatMessage system(UUID id, UUID conversationId, long seq, String body, Visibility visibility,
                                     UUID contextId, Instant now) {
        return new ChatMessage(id, conversationId, seq, MessageKind.SYSTEM, visibility, SenderType.SYSTEM, null,
                requireText(body), null, contextId, null, now, null, null, null, Set.of(), List.of());
    }

    /**
     * The card after its action or its document moved (package 3): a new state and, when given, a new payload.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a message that is no card
     */
    public ChatMessage withCard(CardState state, String payloadJson, Instant now) {
        if (card == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "This message is not a card");
        }
        Card next = new Card(card.kind(), payloadJson == null ? card.payloadJson() : payloadJson,
                state == null ? card.state() : state, now);
        return new ChatMessage(id, conversationId, seq, kind, visibility, senderType, senderId, body, clientMessageId,
                contextId, next, sentAt, editedAt, deletedAt, deletedBy, mentions, attachments);
    }

    /**
     * Its author corrects what they wrote, within {@code window} of sending it (decided 2026-11-13: 5 minutes). Only a
     * text or a note: a card is drawn from a document and changes with it (BR-03), a system line is the system's, and a
     * file is deleted rather than edited. The new text is masked as the first was (BR-08).
     *
     * @throws BusinessException {@code CHAT_MESSAGE_NOT_EDITABLE}; {@code CHAT_EDIT_WINDOW_PASSED}
     */
    public ChatMessage edit(Actor by, String text, Instant now, java.time.Duration window) {
        if (kind != MessageKind.TEXT && kind != MessageKind.INTERNAL_NOTE) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_EDITABLE, "Only a text or a note is edited");
        }
        requireAuthorInWindow(by, now, window);
        String next = written(text);
        if (next.equals(body)) {
            return this;
        }
        return new ChatMessage(id, conversationId, seq, kind, visibility, senderType, senderId, next, clientMessageId,
                contextId, card, sentAt, now, deletedAt, deletedBy, mentions, attachments);
    }

    /**
     * Its author takes it back, within {@code window} of sending it: soft-deleted - the row stays, for the log of BR-01
     * and the read markers that point at it, and every reader is shown "deleted" in its place. A text, a note or a file;
     * never a card or a system line.
     *
     * @throws BusinessException {@code CHAT_MESSAGE_NOT_EDITABLE}; {@code CHAT_EDIT_WINDOW_PASSED}
     */
    public ChatMessage delete(Actor by, Instant now, java.time.Duration window) {
        if (kind == MessageKind.CARD || kind == MessageKind.SYSTEM) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_EDITABLE, "A card or a system line is not deleted");
        }
        requireAuthorInWindow(by, now, window);
        return new ChatMessage(id, conversationId, seq, kind, visibility, senderType, senderId, body, clientMessageId,
                contextId, card, sentAt, editedAt, now, by.userId(), mentions, attachments);
    }

    private void requireAuthorInWindow(Actor by, Instant now, java.time.Duration window) {
        if (deletedAt != null) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_EDITABLE, "The message was deleted");
        }
        if (by.type() != senderType || !Objects.equals(by.userId(), senderId)) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_EDITABLE, "Only its author changes a message");
        }
        if (now.isAfter(sentAt.plus(window))) {
            throw new BusinessException(ErrorCode.CHAT_EDIT_WINDOW_PASSED);
        }
    }

    /** Whether {@code audience} may see this message at all. */
    public boolean visibleTo(Audience audience) {
        return audience.sees(visibility);
    }

    /** Whether a retried send carrying {@code text} is this message again, rather than a different one. */
    public boolean sameText(String text) {
        return Objects.equals(body, text == null ? null : SensitiveData.mask(text.strip()));
    }

    private static void requirePerson(Actor sender) {
        if (sender.type() == SenderType.SYSTEM) {
            throw new IllegalArgumentException("The system writes system lines and cards");
        }
    }

    /** What a person wrote, as it is stored: required, bounded, and masked (BR-08) before it is ever saved. */
    private static String written(String body) {
        return SensitiveData.mask(requireText(body));
    }

    private static String requireText(String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A message says something");
        }
        if (text.length() > MAX_BODY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A message is at most " + MAX_BODY + " characters");
        }
        return text;
    }
}
