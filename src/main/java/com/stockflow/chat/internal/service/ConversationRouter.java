package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;

import java.util.Optional;
import java.util.UUID;

/**
 * Who a new conversation goes to (kltn-docs 16 §4.1 step 2, sales-owner plan §2.3): the customer's Sales owner, or
 * the owner's cover in force today; otherwise the queue, for a Sales Manager to assign or a salesperson to claim.
 * {@link SalesRouter} is the rule.
 */
public interface ConversationRouter {

    /** @return the agent to assign; empty to queue it */
    Optional<UUID> agentFor(Conversation conversation);
}
