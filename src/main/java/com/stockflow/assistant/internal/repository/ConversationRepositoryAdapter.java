package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.Conversation;
import com.stockflow.assistant.internal.domain.ConversationOrigin;
import com.stockflow.assistant.internal.domain.ConversationRepository;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Message;
import com.stockflow.assistant.internal.domain.PendingMessage;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.entity.AssistantConversationJpaEntity;
import com.stockflow.assistant.internal.entity.AssistantMessageJpaEntity;
import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.entity.AssistantToolCallJpaEntity;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ConversationRepository} over three Spring Data repositories.
 *
 * <p><b>Ownership first, then the lock.</b> Every write starts with {@code findByIdInScope}, which applies the OWN
 * scope the endpoint put in force, and only then locks the row by id. Locking first would let a caller block a
 * conversation they cannot even see.</p>
 *
 * <p><b>The lock is refreshed.</b> {@code findByIdForUpdate} after {@code findByIdInScope} would otherwise return the
 * copy already in the persistence context, read without a lock, and the busy check below would run on stale data —
 * two questions racing would both see the conversation free.</p>
 */
@Repository
class ConversationRepositoryAdapter implements ConversationRepository {

    private final AssistantConversationJpaRepository conversations;
    private final AssistantMessageJpaRepository messages;
    private final AssistantToolCallJpaRepository toolCalls;
    private final TurnCodec codec;
    private final EntityManager entityManager;

    ConversationRepositoryAdapter(AssistantConversationJpaRepository conversations, AssistantMessageJpaRepository messages,
                                  AssistantToolCallJpaRepository toolCalls, TurnCodec codec, EntityManager entityManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.toolCalls = toolCalls;
        this.codec = codec;
        this.entityManager = entityManager;
    }

    @Override
    public Conversation create(UUID ownerUserId, Instant at) {
        return toDomain(conversations.save(new AssistantConversationJpaEntity(Identifiers.newId(), ownerUserId, at)));
    }

    @Override
    public Conversation createProactive(UUID ownerUserId, Instant at) {
        return toDomain(conversations.save(new AssistantConversationJpaEntity(Identifiers.newId(), ownerUserId, at,
                ConversationOrigin.PROACTIVE)));
    }

    @Override
    public long proactiveSince(UUID ownerUserId, Instant since) {
        return conversations.countByOwnerUserIdAndOriginAndCreatedAtGreaterThanEqual(ownerUserId,
                ConversationOrigin.PROACTIVE, since);
    }

    @Override
    public Optional<Conversation> findInScope(UUID id) {
        return conversations.findByIdInScope(id).map(ConversationRepositoryAdapter::toDomain);
    }

    @Override
    public PageResponse<Conversation> listInScope(int page, int size) {
        Page<AssistantConversationJpaEntity> found = conversations.findAllInScope(null, Pages.of(page, size,
                Sort.by(Sort.Order.desc("lastActivityAt"), Sort.Order.desc("id"))));
        return PageResponse.of(found.getContent().stream().map(ConversationRepositoryAdapter::toDomain).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements());
    }

    @Override
    public List<Message> messagesInScope(UUID conversationId) {
        if (conversations.findByIdInScope(conversationId).isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ToolExecution>> executions = new LinkedHashMap<>();
        for (AssistantToolCallJpaEntity call : toolCalls.findByConversationIdOrderByIdAsc(conversationId)) {
            executions.computeIfAbsent(call.getMessageId(), id -> new ArrayList<>()).add(new ToolExecution(
                    new ToolCall(call.getCallId(), call.getToolName(), call.getArguments()), call.getOutcome(),
                    call.getResult(), call.getDurationMs()));
        }
        return messages.findByConversationIdOrderBySequenceAsc(conversationId).stream()
                .map(row -> toDomain(row, executions.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    @Override
    public List<Turn> claimAndAsk(UUID conversationId, PendingMessage question, Instant at, Duration lease) {
        AssistantConversationJpaEntity conversation = lockOwned(conversationId);
        if (toDomain(conversation).isAnswering(at, lease)) {
            throw new BusinessException(ErrorCode.ASSISTANT_BUSY,
                    "A question in this conversation is still being answered");
        }
        conversation.claim(at, Conversation.titleFor(question.text()));
        insert(conversation, question, at);
        return messages.findByConversationIdOrderBySequenceAsc(conversationId).stream()
                .map(row -> codec.read(row.getContent()))
                .toList();
    }

    @Override
    public List<Message> appendAndRelease(UUID conversationId, List<PendingMessage> produced, Instant at,
                                          Instant claimedAt, ToolChannel channel) {
        AssistantConversationJpaEntity conversation = lockOwned(conversationId);
        List<Message> stored = new ArrayList<>();
        for (PendingMessage message : produced) {
            stored.add(insert(conversation, message, at, channel));
        }
        conversation.release(claimedAt);
        return stored;
    }

    @Override
    public void release(UUID conversationId, Instant claimedAt) {
        lockOwned(conversationId).release(claimedAt);
    }

    @Override
    public long questionsSince(UUID ownerUserId, Instant since) {
        return messages.countQuestionsSince(ownerUserId, since, PendingMessage.CLOSING_MODEL);
    }

    @Override
    public int deleteIdleBefore(Instant cutoff, int limit) {
        return conversations.deleteIdleBefore(cutoff, limit);
    }

    @Override
    public int deleteAbandonedProactiveBefore(Instant cutoff, int limit) {
        return conversations.deleteAbandonedProactiveBefore(cutoff, limit);
    }

    private AssistantConversationJpaEntity lockOwned(UUID conversationId) {
        conversations.findByIdInScope(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        AssistantConversationJpaEntity locked = conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
        entityManager.refresh(locked);
        return locked;
    }

    @Override
    public boolean deleteEmptyProactive(UUID conversationId, UUID ownerUserId) {
        return conversations.deleteEmptyProactive(conversationId, ownerUserId, ConversationOrigin.PROACTIVE) == 1;
    }

    private Message insert(AssistantConversationJpaEntity conversation, PendingMessage pending, Instant at) {
        return insert(conversation, pending, at, ToolChannel.CHAT);
    }

    /** @param channel the channel of the question being answered: a proactive conversation's follow-ups are CHAT */
    private Message insert(AssistantConversationJpaEntity conversation, PendingMessage pending, Instant at,
                           ToolChannel channel) {
        int sequence = conversation.nextSequence(at);
        LanguageModel.Usage usage = pending.usage();
        boolean modelTurn = pending.model() != null;
        AssistantMessageJpaEntity row = messages.save(new AssistantMessageJpaEntity(Identifiers.newId(),
                conversation.getId(), sequence, pending.role(), pending.text(), codec.write(pending.turn()),
                modelTurn ? pending.stopReason().name() : null, pending.model(),
                modelTurn ? Math.toIntExact(usage.inputTokens()) : null,
                modelTurn ? Math.toIntExact(usage.outputTokens()) : null,
                modelTurn ? Math.toIntExact(usage.cacheReadTokens()) : null,
                modelTurn ? Math.toIntExact(usage.cacheWriteTokens()) : null));
        for (ToolExecution execution : pending.executions()) {
            toolCalls.save(new AssistantToolCallJpaEntity(Identifiers.newId(), channel,
                    conversation.getOwnerUserId(), conversation.getId(), row.getId(), execution.call().id(),
                    execution.call().name(), ToolRunRepositoryAdapter.storable(execution.call().arguments()),
                    execution.outcome(), execution.result(), (int) Math.min(Integer.MAX_VALUE, execution.durationMs())));
        }
        return toDomain(row, pending.executions());
    }

    private Message toDomain(AssistantMessageJpaEntity row, List<ToolExecution> executions) {
        boolean modelTurn = row.getModel() != null;
        LanguageModel.Usage usage = modelTurn
                ? new LanguageModel.Usage(orZero(row.getInputTokens()), orZero(row.getOutputTokens()),
                orZero(row.getCacheReadTokens()), orZero(row.getCacheWriteTokens()))
                : LanguageModel.Usage.NONE;
        return new Message(row.getId(), row.getSequence(), row.getRole(), row.getText(), codec.read(row.getContent()),
                row.getModel(), modelTurn ? LanguageModel.StopReason.valueOf(row.getStopReason()) : null, usage,
                executions, row.getCreatedAt());
    }

    private static Conversation toDomain(AssistantConversationJpaEntity row) {
        return new Conversation(row.getId(), row.getOwnerUserId(), row.getTitle(), row.getMessageCount(),
                row.getLastActivityAt(), row.getAnsweringSince(), row.getCreatedAt(), row.getOrigin());
    }

    private static long orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
