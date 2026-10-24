package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.ConversationRepository;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.MessageRole;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversations and their transcripts: the short transactions around an answer, and the reads behind the panel.
 *
 * <p>Every read and write goes through the store's scoped lookups, so it runs under the OWN scope the endpoint put
 * in force: a user reaches only their own conversations, and another user's id is a 404.</p>
 */
@Service
@Transactional
public class ConversationService {

    private final ConversationRepository conversations;
    private final Clock clock;

    ConversationService(ConversationRepository conversations, Clock clock) {
        this.conversations = conversations;
        this.clock = clock;
    }

    public ConversationView start(CurrentUser user) {
        return ConversationView.of(conversations.create(user.userId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationView> mine(int page, int size) {
        PageResponse<Conversation> found = conversations.listInScope(page, size);
        return PageResponse.of(found.items().stream().map(ConversationView::of).toList(), found.page(), found.size(),
                found.totalElements());
    }

    /**
     * What a person reads: questions and answers, each answer with the tool calls made for it. Tool results and
     * tool-only turns are not messages to anyone; their calls are folded into the answer that follows them.
     */
    @Transactional(readOnly = true)
    public List<TranscriptEntry> transcript(UUID conversationId) {
        conversations.findInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        List<TranscriptEntry> entries = new ArrayList<>();
        List<ToolCallView> calls = new ArrayList<>();
        for (Message message : conversations.messagesInScope(conversationId)) {
            switch (message.role()) {
                case USER -> {
                    calls.clear();
                    entries.add(new TranscriptEntry(message.id(), message.sequence(), MessageRole.USER,
                            message.text(), message.createdAt(), List.of(), false));
                }
                case ASSISTANT -> {
                    message.executions().forEach(execution -> calls.add(ToolCallView.of(execution)));
                    if (message.text() != null) {
                        entries.add(new TranscriptEntry(message.id(), message.sequence(), MessageRole.ASSISTANT,
                                message.text(), message.createdAt(), List.copyOf(calls),
                                message.stopReason() == LanguageModel.StopReason.MAX_TOKENS));
                        calls.clear();
                    }
                }
                case TOOL_RESULTS -> {
                    // Already folded into the calls of the turn that asked for them.
                }
            }
        }
        return entries;
    }

    /** @see ConversationRepository#claimAndAsk */
    public List<Turn> claimAndAsk(UUID conversationId, PendingMessage question, Duration lease) {
        return conversations.claimAndAsk(conversationId, question, clock.instant(), lease);
    }

    /** @see ConversationRepository#appendAndRelease */
    public List<Message> appendAndRelease(UUID conversationId, List<PendingMessage> produced) {
        return conversations.appendAndRelease(conversationId, produced, clock.instant());
    }

    @Transactional(readOnly = true)
    public long questionsSince(UUID ownerUserId, Instant since) {
        return conversations.questionsSince(ownerUserId, since);
    }

    /** @see ConversationRepository#deleteIdleBefore */
    public int deleteIdleBefore(Instant cutoff, int limit) {
        return conversations.deleteIdleBefore(cutoff, limit);
    }

    /** @param title null until the first question */
    public record ConversationView(UUID id, String title, int messageCount, Instant lastActivityAt,
                                   Instant createdAt) {

        static ConversationView of(Conversation conversation) {
            return new ConversationView(conversation.id(), conversation.title(), conversation.messageCount(),
                    conversation.lastActivityAt(), conversation.createdAt());
        }
    }

    /**
     * @param toolCalls the tools run to produce an answer; empty for a question
     * @param cutShort  the answer hit the output limit and may be incomplete
     */
    public record TranscriptEntry(UUID messageId, int sequence, MessageRole role, String text, Instant at,
                                  List<ToolCallView> toolCalls, boolean cutShort) {
    }

    public record ToolCallView(String tool, ToolOutcome outcome) {

        static ToolCallView of(ToolExecution execution) {
            return new ToolCallView(execution.call().name(), execution.outcome());
        }
    }
}
