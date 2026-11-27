package com.stockflow.assistant.internal.domain;

/**
 * Who opened a conversation (also {@code ck_conversation_origin}).
 *
 * <p>A PROACTIVE one is opened by the assistant's watch for its owner, with a question the watch asked (design §9.1):
 * still the owner's to read and continue, but its question is not theirs, so it never counts against their daily
 * limit, and its tool runs are recorded on the PROACTIVE channel.</p>
 */
public enum ConversationOrigin {
    CHAT,
    PROACTIVE
}
