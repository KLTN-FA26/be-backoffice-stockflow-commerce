package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.service.ChatCallers;

/** Permission resource codes owned by the chat module (V20261124000300). */
final class ChatResources {

    /**
     * Conversations: the customer (their own, on the portal: scope OWN), Sales, the Sales Manager and the administrator.
     * VIEW_PAGE and READ to see, CREATE to open one, UPDATE to write, read, claim, hand on and move it.
     */
    static final String CONVERSATIONS = ChatCallers.CONVERSATIONS;
    /** Chat supervision (16 §2): assigning from the queue and taking escalations. Asked for by the service. */
    static final String SUPERVISION = ChatCallers.SUPERVISION;
    /** Saved replies (package 3). */
    static final String CANNED_RESPONSES = ChatCallers.CANNED_RESPONSES;

    private ChatResources() {
    }
}
