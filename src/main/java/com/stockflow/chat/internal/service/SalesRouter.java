package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleSales;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The routing of kltn-docs 16 §4.1 step 2 since 2026-11-28 (sales-owner plan §2.3, 16 BR-12): one customer, one
 * contact. A new conversation goes to whoever acts for the customer today by the one rule of {@link ResponsibleSales}
 * - the Sales owner, or the owner's cover in force today (BR-S07) - whatever it is about: a quote another salesperson
 * sent still reaches the owner (cases 3, 4). With no active owner it waits in the queue (case 2, 8), where a Sales
 * Manager assigns it or a salesperson claims it (BR-S05).
 *
 * <p>The steps it replaced are gone on purpose: "the Sales of the document" gave one customer several contacts, and "the
 * agent who served the customer last" handed a customer to someone who no longer looks after them.</p>
 *
 * <p>The acting user gets the conversation only while they answer customers ({@link AgentDirectory}: an active account
 * holding {@code chat-conversations:UPDATE}); otherwise the queue, never a conversation nobody will read. Being offline
 * does not count: the first-response alert (BR-09) catches an owner who does not answer, cover or not (case 6). Which
 * cover is in force is read when the conversation opens; a cover starting or ending moves no open conversation (F9).</p>
 */
@Component
public class SalesRouter implements ConversationRouter {

    private final ResponsibleSales responsible;
    private final AgentDirectory agents;
    private final Clock clock;

    public SalesRouter(ResponsibleSales responsible, AgentDirectory agents, Clock clock) {
        this.responsible = responsible;
        this.agents = agents;
        this.clock = clock;
    }

    @Override
    public Optional<UUID> agentFor(Conversation conversation) {
        return responsible.responsibleFor(conversation.customerId(), BusinessCalendar.date(clock.instant()))
                .map(Responsible::actingUserId)
                .filter(agents::answersCustomers);
    }
}
