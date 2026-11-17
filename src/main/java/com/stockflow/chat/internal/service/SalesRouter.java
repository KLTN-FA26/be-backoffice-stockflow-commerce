package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The routing of kltn-docs 16 §4.1 step 2, in its order:
 * <ol>
 *   <li>the Sales account that answers for the document the conversation is about - who sent the quote or the
 *       contract, or the quote an order or a production order came from (2.1: a production order's Sales is its sales
 *       order's, since the Production Coordinator does not chat with customers);</li>
 *   <li>the Sales account that looked after the customer most recently (2.2), so a customer keeps talking to the
 *       person who knows them;</li>
 *   <li>nobody: the conversation waits in the queue for a chat supervisor to assign (2.3).</li>
 * </ol>
 * At each step only someone who still answers customers counts ({@link AgentDirectory}: an active account holding
 * {@code chat-conversations:UPDATE}) - a Sales who left is skipped, not handed a conversation nobody will read. The
 * set of agents is read once per routing.
 *
 * <p>Being offline does not skip anyone: the conversation is the customer's relationship with that agent, and outside
 * business hours everyone is offline; the first-response alert (BR-09) is what catches an agent who does not answer.</p>
 */
@Component
public class SalesRouter implements ConversationRouter {

    /** How far back the customer's agents are looked for: a handful is enough to find one still at work. */
    private static final int RECENT_AGENTS = 5;

    private final AgentDirectory agents;
    private final ConversationRepository conversations;

    public SalesRouter(AgentDirectory agents, ConversationRepository conversations) {
        this.agents = agents;
        this.conversations = conversations;
    }

    @Override
    public Optional<UUID> agentFor(Conversation conversation, List<ContextResolver.Resolved> contexts) {
        Set<UUID> answering = agents.agents();
        Optional<UUID> owner = contexts.stream().map(ContextResolver.Resolved::responsibleUserId)
                .filter(Objects::nonNull).filter(answering::contains).findFirst();
        if (owner.isPresent()) {
            return owner;
        }
        return conversations.recentAgentsOf(conversation.customerId(), conversation.id(), RECENT_AGENTS).stream()
                .filter(answering::contains).findFirst();
    }
}
