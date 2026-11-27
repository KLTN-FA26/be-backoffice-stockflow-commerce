package com.stockflow.assistant.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One user's conversation with the assistant.
 *
 * @param title          the first question, cut to {@link #TITLE_LENGTH}; null until one is asked
 * @param messageCount   messages stored so far; the next one's sequence
 * @param answeringSince when the question being answered now was claimed; null when none is
 * @param origin         who opened it: its owner, or the assistant's watch for them
 */
public record Conversation(UUID id, UUID ownerUserId, String title, int messageCount, Instant lastActivityAt,
                           Instant answeringSince, Instant createdAt, ConversationOrigin origin) {

    /** Also {@code ck_conversation_title}. */
    public static final int TITLE_LENGTH = 120;

    /** The longest question accepted; also {@code ck_message_question}. */
    public static final int QUESTION_LENGTH = 4000;

    public Conversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
        origin = origin == null ? ConversationOrigin.CHAT : origin;
    }

    /** One its owner opened. */
    public Conversation(UUID id, UUID ownerUserId, String title, int messageCount, Instant lastActivityAt,
                        Instant answeringSince, Instant createdAt) {
        this(id, ownerUserId, title, messageCount, lastActivityAt, answeringSince, createdAt, ConversationOrigin.CHAT);
    }

    /**
     * Whether a question is being answered right now. A claim older than {@code lease} is treated as abandoned: it is
     * released in a {@code finally} that a killed process never reaches, and must not lock the conversation forever.
     */
    public boolean isAnswering(Instant now, Duration lease) {
        return answeringSince != null && answeringSince.plus(lease).isAfter(now);
    }

    /**
     * A question as stored and sent: trimmed, 1..{@value #QUESTION_LENGTH} characters.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} otherwise
     */
    public static String question(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > QUESTION_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A question is 1 to " + QUESTION_LENGTH + " characters",
                    List.of(new FieldError("text", "must be 1 to " + QUESTION_LENGTH + " characters", "Size")));
        }
        return trimmed;
    }

    /** The title a first question gives a conversation: one line, cut with an ellipsis to {@value #TITLE_LENGTH}. */
    public static String titleFor(String question) {
        String line = question.strip().replaceAll("\\s+", " ");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH - 1).stripTrailing() + "…";
    }
}
