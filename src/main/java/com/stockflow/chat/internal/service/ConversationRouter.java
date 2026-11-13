package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a new conversation goes to (kltn-docs 16 §4.1 step 2): the Sales owner of the document it is about, then whoever
 * looked after the customer last, then the queue for a supervisor to assign.
 *
 * <p>The extension point for package 3's routing (working hours, the customer's last agent, load). The foundation's
 * {@link DocumentOwnerRouter} does the first step only and queues the rest.</p>
 */
public interface ConversationRouter {

    /**
     * @param contexts what the new conversation is about, the primary first
     * @return the agent to assign; empty to queue it
     */
    Optional<UUID> agentFor(Conversation conversation, List<ContextResolver.Resolved> contexts);
}
