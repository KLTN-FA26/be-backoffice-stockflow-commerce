package com.stockflow.chat.api;

import com.stockflow.common.security.CurrentUser;

import java.util.UUID;

/**
 * The recent messages of one conversation and the documents it is about, read for a module that helps the staff
 * answer it without owning it - the back-office assistant's reply draft and summary (assistant contract §1.2, §1.3).
 *
 * <p><b>The staff screen's access, nothing wider.</b> Only a back-office account holding {@code chat-conversations:READ}
 * reads a conversation here, as the staff chat screen ({@code GET /api/v1/chat/conversations/{id}/messages}) does: a
 * portal account is refused before anything is read, whatever permission it holds. The caller is passed in rather than
 * read here so the check is the screen's own, made by the module that owns the rule.</p>
 *
 * <p>Read-only, two statements and a third for the contexts; nothing is marked read and nothing is broadcast.</p>
 */
public interface ChatTranscripts {

    /** The most messages one read returns: a draft or a summary of the recent part of a conversation. */
    int MAX_MESSAGES = 50;

    /**
     * The newest {@code limit} messages of the conversation, oldest first, with its contexts.
     *
     * @param withInternalNotes true for the staff's side (internal notes and staff-only system lines included); false
     *                          for exactly what the customer was shown - what a reply to the customer may build on
     * @throws com.stockflow.common.error.BusinessException {@code FORBIDDEN} for a portal account or a caller without
     *         {@code chat-conversations:READ}; {@code CHAT_CONVERSATION_NOT_FOUND} for no such conversation
     */
    ChatTranscript recent(UUID conversationId, int limit, boolean withInternalNotes, CurrentUser caller);
}
