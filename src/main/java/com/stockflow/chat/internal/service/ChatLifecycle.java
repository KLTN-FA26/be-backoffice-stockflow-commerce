package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The conversation's clocks (kltn-docs 16 §5, BR-09), one conversation per transaction: a late first answer alerts the
 * chat supervisors once; a conversation waiting on a customer silent for {@link ChatProperties#autoCloseAfter} is
 * auto-closed; one resolved or auto-closed past its {@link ChatProperties#reopenWindow} is closed for good.
 *
 * <p>Each step locks its conversation and asks again whether it still applies - a customer may have written, an agent
 * answered, between the job's list and the lock - so a step that no longer applies does nothing, and the job may run on
 * a list a moment old. {@link ChatLifecycleJobs} calls these, each through this bean's proxy, so one conversation's
 * failure never rolls back another's.</p>
 */
@Service
@Transactional
public class ChatLifecycle {

    private final ConversationRepository conversations;
    private final ConversationWriter writer;
    private final ChatNotifier notifier;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatLifecycle(ConversationRepository conversations, ConversationWriter writer, ChatNotifier notifier,
                         ChatProperties properties, Clock clock) {
        this.conversations = conversations;
        this.writer = writer;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UUID> overdueFirstResponses() {
        return conversations.firstResponseOverdueUnalerted(clock.instant(), properties.slaJobBatch());
    }

    @Transactional(readOnly = true)
    public List<UUID> silentCustomers() {
        return conversations.waitingOnCustomerSince(clock.instant().minus(properties.autoCloseAfter()),
                properties.slaJobBatch());
    }

    @Transactional(readOnly = true)
    public List<UUID> pastReopenWindow() {
        return conversations.pastReopenWindow(clock.instant(), properties.slaJobBatch());
    }

    /** A late first answer: FIRST_RESPONSE_OVERDUE logged and the supervisors told, once. */
    public boolean alertFirstResponse(UUID conversationId) {
        Conversation c = writer.lock(conversationId);
        UUID agent = c.assignedAgentId();
        if (!c.alertFirstResponseOverdue(clock.instant())) {
            return false;
        }
        writer.save(c, agent);
        notifier.firstResponseOverdue(c);
        return true;
    }

    /** A customer silent too long while the conversation waits on them: AUTO_CLOSED, reopenable for the window. */
    public boolean autoClose(UUID conversationId) {
        Conversation c = writer.lock(conversationId);
        Instant now = clock.instant();
        if (c.status() != ConversationStatus.WAITING_ON_CUSTOMER
                || c.statusChangedAt().isAfter(now.minus(properties.autoCloseAfter()))) {
            return false;
        }
        UUID agent = c.assignedAgentId();
        c.autoClose(now, properties.reopenWindow());
        writer.save(c, agent);
        return true;
    }

    /** The reopen window passed: CLOSED; the customer's next word starts a conversation continuing it. */
    public boolean close(UUID conversationId) {
        Conversation c = writer.lock(conversationId);
        Instant now = clock.instant();
        if ((c.status() != ConversationStatus.RESOLVED && c.status() != ConversationStatus.AUTO_CLOSED)
                || c.reopensAt(now)) {
            return false;
        }
        UUID agent = c.assignedAgentId();
        c.close(now);
        writer.save(c, agent);
        return true;
    }
}
