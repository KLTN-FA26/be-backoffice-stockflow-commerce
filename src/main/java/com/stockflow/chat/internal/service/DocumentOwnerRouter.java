package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The foundation's routing (16 §4.1 step 2.1, 2.3): a conversation about a document goes to the Sales account that
 * answers for it - who sent the quote or the contract, or the quote an order came from - as long as that account still
 * answers customers; everything else waits in the queue. Package 3 replaces this bean with the full rule.
 */
@Component
public class DocumentOwnerRouter implements ConversationRouter {

    private final AgentDirectory agents;

    public DocumentOwnerRouter(AgentDirectory agents) {
        this.agents = agents;
    }

    @Override
    public Optional<UUID> agentFor(Conversation conversation, List<ContextResolver.Resolved> contexts) {
        return contexts.stream().map(ContextResolver.Resolved::responsibleUserId).filter(Objects::nonNull)
                .filter(agents::answersCustomers).findFirst();
    }
}
