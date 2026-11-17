package com.stockflow.chat.internal.service;

import java.util.List;

/**
 * A page of messages.
 *
 * <p>Paging backwards ({@code before}), the newest come first and {@link #nextBefore} is the cursor of the next, older
 * page (null when there is none). Catching up ({@code since}, after the socket was lost), the oldest come first and
 * {@link #nextSince} is the cursor to ask from next (null when nothing more is waiting).</p>
 *
 * @param lastSeq the newest sequence the caller may see in the conversation now
 */
public record ChatMessagePage(List<ChatMessageView> items, Long nextBefore, Long nextSince, long lastSeq) {
}
