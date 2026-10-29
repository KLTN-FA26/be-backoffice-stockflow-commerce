package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final ObjectMapper json;

    ConversationService(ConversationRepository conversations, Clock clock, ObjectMapper json) {
        this.conversations = conversations;
        this.clock = clock;
        this.json = json;
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
     * What a person reads: each question, then the answer it got — the last text the assistant wrote for it, with
     * every tool call made for it, read exactly as the live answer is ({@link TranscriptEntry#answer}). Tool results
     * and tool-only turns are not messages to anyone, and neither is an interim line the model wrote beside a tool
     * call ("let me check the rule"): its calls, citations and proposals belong to the answer that follows.
     */
    @Transactional(readOnly = true)
    public List<TranscriptEntry> transcript(UUID conversationId) {
        conversations.findInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        List<TranscriptEntry> entries = new ArrayList<>();
        List<Message> exchange = new ArrayList<>();
        for (Message message : conversations.messagesInScope(conversationId)) {
            if (message.role() != MessageRole.USER) {
                exchange.add(message);
                continue;
            }
            TranscriptEntry.answer(exchange, json).ifPresent(entries::add);
            exchange.clear();
            entries.add(new TranscriptEntry(message.id(), message.sequence(), MessageRole.USER, message.text(),
                    message.createdAt(), List.of(), false, List.of(), List.of()));
        }
        TranscriptEntry.answer(exchange, json).ifPresent(entries::add);
        return entries;
    }

    /**
     * @param claimedAt the claim's time, at the column's precision (microseconds), so the release can recognise it
     * @see ConversationRepository#claimAndAsk
     */
    public List<Turn> claimAndAsk(UUID conversationId, PendingMessage question, Instant claimedAt, Duration lease) {
        return conversations.claimAndAsk(conversationId, question, claimedAt, lease);
    }

    /**
     * Refuses a question the way {@link #claimAndAsk} would, without claiming: for a stream, whose refusals must be
     * ordinary responses and not events inside a 200. The claim still checks again; this only answers early.
     *
     * @throws NotFoundException when the conversation is not the caller's
     * @throws BusinessException {@code ASSISTANT_BUSY} while a question in it is being answered
     */
    @Transactional(readOnly = true)
    public void requireIdle(UUID conversationId, Duration lease) {
        Conversation conversation = conversations.findInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        if (conversation.isAnswering(clock.instant(), lease)) {
            throw new BusinessException(ErrorCode.ASSISTANT_BUSY,
                    "A question in this conversation is still being answered");
        }
    }

    /** @see ConversationRepository#appendAndRelease */
    public List<Message> appendAndRelease(UUID conversationId, Instant claimedAt, List<PendingMessage> produced) {
        return conversations.appendAndRelease(conversationId, produced, clock.instant(), claimedAt);
    }

    /**
     * Releases the claim made at {@code claimedAt}, in a transaction of its own, after an {@link #appendAndRelease}
     * that failed: its rollback took the release down with the rest, and the conversation would otherwise refuse every
     * question for the rest of the lease with nothing answering. A claim a later question took over is left alone.
     *
     * @see ConversationRepository#release
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID conversationId, Instant claimedAt) {
        conversations.release(conversationId, claimedAt);
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
     * @param citations rule anchors the answer names that its own searches returned
     * @param proposals purchase orders the panel may open pre-filled
     */
    public record TranscriptEntry(UUID messageId, int sequence, MessageRole role, String text, Instant at,
                                  List<ToolCallView> toolCalls, boolean cutShort, List<String> citations,
                                  List<AnswerSources.Proposal> proposals) {

        /**
         * The answer read from the messages stored for one question: its last text, every tool call made for it, the
         * rules that text cites and the proposals those calls returned; cut short if any of its replies was. The live
         * answer ({@code AssistantService.Answer}) and the reopened transcript are both read here, so the tool chips,
         * citations and proposals shown under an answer cannot move to another line when the conversation is
         * reopened.
         *
         * @param exchange the messages after the question, in order
         * @return empty while the question is still being answered: no message holds text yet
         */
        static Optional<TranscriptEntry> answer(List<Message> exchange, ObjectMapper json) {
            Message last = null;
            List<ToolExecution> executions = new ArrayList<>();
            boolean cutShort = false;
            for (Message message : exchange) {
                if (message.role() != MessageRole.ASSISTANT) {
                    continue;
                }
                executions.addAll(message.executions());
                cutShort |= message.stopReason() == LanguageModel.StopReason.MAX_TOKENS;
                if (message.text() != null && !message.text().isBlank()) {
                    last = message;
                }
            }
            if (last == null) {
                return Optional.empty();
            }
            return Optional.of(new TranscriptEntry(last.id(), last.sequence(), MessageRole.ASSISTANT, last.text(),
                    last.createdAt(), executions.stream().map(ToolCallView::of).toList(), cutShort,
                    AnswerSources.citations(last.text(), executions, json),
                    AnswerSources.proposals(executions, json)));
        }
    }

    public record ToolCallView(String tool, ToolOutcome outcome) {

        static ToolCallView of(ToolExecution execution) {
            return new ToolCallView(execution.call().name(), execution.outcome());
        }
    }
}
