package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.AnswerFeedback;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.ConversationRepository;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.History;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations and their transcripts: the short transactions around an answer, the reads behind the panel, and the
 * verdicts people give answers.
 *
 * <p>Every read and write goes through the store's scoped lookups, so it runs under the OWN scope the endpoint put
 * in force: a user reaches only their own conversations, and another user's id is a 404.</p>
 */
@Service
@Transactional
public class ConversationService {

    private final ConversationRepository conversations;
    private final AssistantActions actions;
    private final AnswerFeedbackRepository verdicts;
    private final Clock clock;
    private final ObjectMapper json;

    ConversationService(ConversationRepository conversations, AnswerFeedbackRepository verdicts, Clock clock,
                        ObjectMapper json) {
        this(conversations, verdicts, clock, json, null);
    }

    /** @param actions the suggestions answers make (ADR-0011); null in tests of the transcript alone */
    @org.springframework.beans.factory.annotation.Autowired
    ConversationService(ConversationRepository conversations, AnswerFeedbackRepository verdicts, Clock clock,
                        ObjectMapper json, AssistantActions actions) {
        this.actions = actions;
        this.conversations = conversations;
        this.verdicts = verdicts;
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
     * call ("let me check the rule"): its calls, citations, proposals and tables belong to the answer that follows.
     * Each answer carries the verdict its owner gave it, if any.
     *
     * <p>The numbers of an answer may rest on the {@value History#EARLIER_QUESTIONS} exchanges before it
     * ({@link AnswerSources}): each exchange's grounds are kept for the answers after it, instead of being worked out
     * again for every one of them.</p>
     */
    @Transactional(readOnly = true)
    public List<TranscriptEntry> transcript(UUID conversationId) {
        conversations.findInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        List<TranscriptEntry> entries = new ArrayList<>();
        Deque<AnswerNumbers.Grounds> earlier = new ArrayDeque<>();
        Turn.Question asked = null;
        List<Message> exchange = new ArrayList<>();
        for (Message message : conversations.messagesInScope(conversationId)) {
            if (message.role() != MessageRole.USER) {
                exchange.add(message);
                continue;
            }
            answer(asked, exchange, earlier, entries);
            asked = (Turn.Question) message.turn();
            exchange = new ArrayList<>();
            entries.add(TranscriptEntry.question(message));
        }
        answer(asked, exchange, earlier, entries);
        return withActions(withVerdicts(entries), conversationId);
    }

    /** Each answer with the suggestions its proposals became, and what their owner decided. */
    private List<TranscriptEntry> withActions(List<TranscriptEntry> entries, UUID conversationId) {
        if (actions == null || entries.stream().allMatch(entry -> entry.proposals().isEmpty())) {
            return entries;
        }
        UUID owner = conversations.findInScope(conversationId).map(Conversation::ownerUserId).orElseThrow();
        Map<UUID, Map<String, AssistantActions.ActionView>> made = actions.ofAnswers(entries.stream()
                .filter(entry -> !entry.proposals().isEmpty()).map(TranscriptEntry::messageId).toList(), owner);
        return entries.stream().map(entry -> made.containsKey(entry.messageId())
                ? entry.withActions(made.get(entry.messageId())) : entry).toList();
    }

    /**
     * Adds the answer {@code exchange} holds for {@code asked}, if it holds one yet, read against the exchanges before
     * it; then keeps this exchange's grounds for the answers after it.
     */
    private void answer(Turn.Question asked, List<Message> exchange, Deque<AnswerNumbers.Grounds> earlier,
                        List<TranscriptEntry> entries) {
        AnswerNumbers.Grounds before = earlier.stream().reduce(AnswerNumbers.Grounds.none(),
                AnswerNumbers.Grounds::and);
        TranscriptEntry.answer(asked, before, exchange, json).ifPresent(entries::add);
        if (asked != null) {
            earlier.addLast(AnswerSources.groundsOf(TranscriptEntry.turns(asked, exchange)));
            if (earlier.size() > History.EARLIER_QUESTIONS) {
                earlier.removeFirst();
            }
        }
    }

    private List<TranscriptEntry> withVerdicts(List<TranscriptEntry> entries) {
        List<UUID> answers = entries.stream().filter(entry -> entry.role() == MessageRole.ASSISTANT)
                .map(TranscriptEntry::messageId).toList();
        Map<UUID, FeedbackVerdict> given = verdicts.verdictsOf(answers);
        if (given.isEmpty()) {
            return entries;
        }
        return entries.stream().map(entry -> entry.withFeedback(given.get(entry.messageId()))).toList();
    }

    /**
     * Records the caller's verdict on one answer of their own conversation, replacing one given before (design §7).
     *
     * @throws NotFoundException when the conversation is not the caller's, or the message is not one of its answers
     */
    public void rateAnswer(UUID conversationId, UUID messageId, CurrentUser user, AnswerFeedback feedback) {
        conversations.findInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        if (!verdicts.record(conversationId, messageId, user.userId(), feedback)) {
            throw new NotFoundException("Answer", messageId);
        }
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

    /**
     * Stores what the loop produced and releases the claim ({@link ConversationRepository#appendAndRelease}), and in
     * the same transaction the purchase orders the answer proposes as suggestions of its owner (ADR-0011): a proposal
     * shown with an answer always has its suggestion to confirm.
     */
    public List<Message> appendAndRelease(UUID conversationId, UUID ownerUserId, Instant claimedAt,
                                          List<PendingMessage> produced) {
        List<Message> stored = conversations.appendAndRelease(conversationId, produced, clock.instant(), claimedAt);
        if (actions != null) {
            suggest(conversationId, ownerUserId, stored);
        }
        return stored;
    }

    /** The answer's proposals, read as the transcript reads them: from every tool of the question, on its last text. */
    private void suggest(UUID conversationId, UUID ownerUserId, List<Message> stored) {
        Message answer = null;
        List<ToolExecution> executions = new ArrayList<>();
        for (Message message : stored) {
            if (message.role() == MessageRole.ASSISTANT) {
                executions.addAll(message.executions());
                if (message.text() != null && !message.text().isBlank()) {
                    answer = message;
                }
            }
        }
        List<AnswerSources.Proposal> proposals = AnswerSources.proposals(executions, json);
        if (answer != null && !proposals.isEmpty()) {
            actions.suggestPurchaseOrders(ownerUserId, conversationId, answer.id(), proposals);
        }
    }

    /** The suggestions {@code messageId}'s answer made, by proposal; empty when it made none. */
    @Transactional(readOnly = true)
    public Map<String, AssistantActions.ActionView> actionsOf(UUID messageId, UUID ownerUserId) {
        if (actions == null) {
            return Map.of();
        }
        return actions.ofAnswers(List.of(messageId), ownerUserId).getOrDefault(messageId, Map.of());
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
     * @param toolCalls         the tools run to produce an answer; empty for a question
     * @param cutShort          the answer hit the output limit and may be incomplete
     * @param citations         rule anchors the answer names that its own searches returned
     * @param proposals         purchase orders the panel may open pre-filled
     * @param tables            the rows its lookups returned, as the panel shows them under it ({@link ResultTable})
     * @param unverifiedNumbers numbers the answer states that nothing the model was given for it holds, as written
     *                          ({@link AnswerNumbers}); advisory
     * @param followUps         the questions its lookups lead to, before any reader's permissions are applied
     *                          ({@link FollowUps})
     * @param feedback          the verdict its owner gave it; null for none, and for a question
     */
    public record TranscriptEntry(UUID messageId, int sequence, MessageRole role, String text, Instant at,
                                  List<ToolCallView> toolCalls, boolean cutShort, List<String> citations,
                                  List<AnswerSources.Proposal> proposals, List<ResultTable.Table> tables,
                                  List<String> unverifiedNumbers, List<FollowUps.FollowUp> followUps,
                                  FeedbackVerdict feedback, Map<String, AssistantActions.ActionView> actions) {

        public TranscriptEntry {
            actions = actions == null ? Map.of() : Map.copyOf(actions);
        }

        static TranscriptEntry question(Message message) {
            return new TranscriptEntry(message.id(), message.sequence(), MessageRole.USER, message.text(),
                    message.createdAt(), List.of(), false, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                    Map.of());
        }

        /**
         * The live answer ({@code AssistantService.Answer}): {@link #answer(Turn.Question, AnswerNumbers.Grounds,
         * List, ObjectMapper)} with the question and the exchanges before it read off {@code asked}.
         *
         * @param asked    the conversation's stored turns, oldest first, ending with the question answered
         * @param exchange the messages the answer produced, in order
         */
        static Optional<TranscriptEntry> answer(List<Turn> asked, List<Message> exchange, ObjectMapper json) {
            List<List<Turn>> exchanges = AnswerSources.exchanges(asked);
            Turn.Question question = exchanges.isEmpty() ? null : (Turn.Question) exchanges.getLast().getFirst();
            return answer(question, AnswerSources.earlierGrounds(asked), exchange, json);
        }

        /**
         * The answer read from the messages stored for one question: its last text, every tool call made for it, the
         * rules that text cites, the proposals and tables those calls returned, the numbers it states that nothing
         * holds and the questions it leads to; cut short if any of its replies up to that text was — a rewrite set
         * aside after it, stored with no text, says nothing about the answer shown. The live answer
         * ({@code AssistantService.Answer}) and the reopened transcript are both read here, so nothing shown under an
         * answer can move to another line, or differ, when the conversation is reopened.
         *
         * @param question the question answered; null only for messages stored before any question
         * @param earlier  the grounds of the exchanges before it ({@link AnswerSources#earlierGrounds})
         * @param exchange the messages after the question, in order
         * @return empty while the question is still being answered: no message holds text yet
         */
        static Optional<TranscriptEntry> answer(Turn.Question question, AnswerNumbers.Grounds earlier,
                                                List<Message> exchange, ObjectMapper json) {
            Message last = null;
            List<ToolExecution> executions = new ArrayList<>();
            boolean cutSoFar = false;
            boolean cutShort = false;
            for (Message message : exchange) {
                if (message.role() != MessageRole.ASSISTANT) {
                    continue;
                }
                executions.addAll(message.executions());
                cutSoFar |= message.stopReason() == LanguageModel.StopReason.MAX_TOKENS;
                if (message.text() != null && !message.text().isBlank()) {
                    last = message;
                    cutShort = cutSoFar;
                }
            }
            if (last == null) {
                return Optional.empty();
            }
            AnswerNumbers.Grounds grounds = earlier.and(AnswerSources.groundsOf(turns(question, exchange)));
            return Optional.of(new TranscriptEntry(last.id(), last.sequence(), MessageRole.ASSISTANT, last.text(),
                    last.createdAt(), executions.stream().map(ToolCallView::of).toList(), cutShort,
                    AnswerSources.citations(last.text(), executions, json),
                    AnswerSources.proposals(executions, json), ResultTable.of(executions, json),
                    AnswerNumbers.unverified(last.text(), grounds), FollowUps.leads(executions, json), null, Map.of()));
        }

        /** The question, if any, then the turns of the messages produced for it. */
        static List<Turn> turns(Turn.Question question, List<Message> exchange) {
            List<Turn> turns = new ArrayList<>(exchange.size() + 1);
            if (question != null) {
                turns.add(question);
            }
            exchange.forEach(message -> turns.add(message.turn()));
            return turns;
        }

        TranscriptEntry withFeedback(FeedbackVerdict verdict) {
            return new TranscriptEntry(messageId, sequence, role, text, at, toolCalls, cutShort, citations, proposals,
                    tables, unverifiedNumbers, followUps, verdict, actions);
        }

        /** With the suggestions of its proposals, by proposal key ({@link AnswerSources.Proposal#key}). */
        TranscriptEntry withActions(Map<String, AssistantActions.ActionView> made) {
            return new TranscriptEntry(messageId, sequence, role, text, at, toolCalls, cutShort, citations, proposals,
                    tables, unverifiedNumbers, followUps, feedback, made);
        }
    }

    public record ToolCallView(String tool, ToolOutcome outcome) {

        static ToolCallView of(ToolExecution execution) {
            return new ToolCallView(execution.call().name(), execution.outcome());
        }
    }
}
