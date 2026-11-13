package com.stockflow.chat.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>A conversation between a customer and Sales</b> (kltn-docs 16): its state of §5, the agent on it, its clocks
 * (first response and resolution, BR-09; the reopen window) and the sequence its messages are numbered by.
 *
 * <p>Every move goes through one method that checks {@link ConversationStatus#canMoveTo} - the table the database's
 * {@code tg_conversation_lifecycle} states again - and writes the line of the log BR-01 asks for. The methods say who
 * may make each move: the customer reopens by writing, the agent in charge (or a supervisor) answers and moves the
 * conversation along, only a supervisor brings one back from an escalation, only the system routes, auto-closes and
 * closes.</p>
 *
 * <p>Messages are not loaded with it. A message is numbered here ({@code accept*Message} returns its sequence) under
 * the conversation's row lock, which is what makes the numbering gapless and a retried send find its first attempt.</p>
 */
public final class Conversation extends AggregateRoot {

    /** Everything the aggregate holds, for the repository to store and restore. */
    public record Snapshot(UUID id, UUID customerId, UUID orderId, String subject, ConversationStatus status,
                           SenderType openedByType, UUID openedBy, UUID assignedAgentId, Instant assignedAt,
                           Instant statusChangedAt, Instant firstResponseDueAt, Instant firstRespondedAt,
                           Instant firstResponseAlertedAt, Instant resolutionDueAt, Instant resolutionAlertedAt,
                           String escalationReason, UUID escalatedToId, Instant resolvedAt, UUID resolvedBy,
                           Instant autoClosedAt, Instant closedAt, Instant reopenUntil,
                           Instant lastCustomerMessageAt, Instant lastMessageAt, long lastMessageSeq,
                           Integer csatScore, String csatComment, Instant csatSubmittedAt, UUID continuedFromId,
                           UUID mergedIntoId, Instant createdAt, long version) {
    }

    private final UUID id;
    private final UUID customerId;
    private final UUID orderId;
    private final String subject;
    private final SenderType openedByType;
    private final UUID openedBy;
    private final UUID continuedFromId;
    private final Instant createdAt;
    private final long version;
    private ConversationStatus status;
    private UUID assignedAgentId;
    private Instant assignedAt;
    private Instant statusChangedAt;
    private Instant firstResponseDueAt;
    private Instant firstRespondedAt;
    private Instant firstResponseAlertedAt;
    private Instant resolutionDueAt;
    private Instant resolutionAlertedAt;
    private String escalationReason;
    private UUID escalatedToId;
    private Instant resolvedAt;
    private UUID resolvedBy;
    private Instant autoClosedAt;
    private Instant closedAt;
    private Instant reopenUntil;
    private Instant lastCustomerMessageAt;
    private Instant lastMessageAt;
    private long lastMessageSeq;
    private Integer csatScore;
    private String csatComment;
    private Instant csatSubmittedAt;
    private UUID mergedIntoId;
    private final List<LogEntry> log = new ArrayList<>();

    private Conversation(Snapshot s) {
        this.id = Objects.requireNonNull(s.id(), "id");
        this.customerId = Objects.requireNonNull(s.customerId(), "customerId");
        this.orderId = s.orderId();
        this.subject = s.subject();
        this.status = Objects.requireNonNull(s.status(), "status");
        this.openedByType = Objects.requireNonNull(s.openedByType(), "openedByType");
        this.openedBy = s.openedBy();
        this.assignedAgentId = s.assignedAgentId();
        this.assignedAt = s.assignedAt();
        this.statusChangedAt = s.statusChangedAt();
        this.firstResponseDueAt = s.firstResponseDueAt();
        this.firstRespondedAt = s.firstRespondedAt();
        this.firstResponseAlertedAt = s.firstResponseAlertedAt();
        this.resolutionDueAt = s.resolutionDueAt();
        this.resolutionAlertedAt = s.resolutionAlertedAt();
        this.escalationReason = s.escalationReason();
        this.escalatedToId = s.escalatedToId();
        this.resolvedAt = s.resolvedAt();
        this.resolvedBy = s.resolvedBy();
        this.autoClosedAt = s.autoClosedAt();
        this.closedAt = s.closedAt();
        this.reopenUntil = s.reopenUntil();
        this.lastCustomerMessageAt = s.lastCustomerMessageAt();
        this.lastMessageAt = s.lastMessageAt();
        this.lastMessageSeq = s.lastMessageSeq();
        this.csatScore = s.csatScore();
        this.csatComment = s.csatComment();
        this.csatSubmittedAt = s.csatSubmittedAt();
        this.continuedFromId = s.continuedFromId();
        this.mergedIntoId = s.mergedIntoId();
        this.createdAt = s.createdAt();
        this.version = s.version();
    }

    /** As stored. */
    public static Conversation restore(Snapshot snapshot) {
        return new Conversation(snapshot);
    }

    /**
     * A new conversation of {@code customerId}, NEW until it is routed (16 §4.1 step 1). Its first answer is due
     * {@code firstResponseTarget} from now (BR-09).
     *
     * @param continuedFromId the ended conversation this one continues (the customer wrote after its reopen window);
     *                        null for a fresh one
     * @param orderId         the order it is about, when opened from an order (the legacy column the consent check
     *                        reads); null otherwise
     */
    public static Conversation open(UUID id, UUID customerId, String subject, UUID orderId, Actor opener,
                                    UUID continuedFromId, Instant now, Duration firstResponseTarget) {
        if (opener.isCustomer() || opener.isStaff()) {
            Objects.requireNonNull(opener.userId(), "opener");
        }
        String title = subject == null || subject.isBlank() ? null : subject.strip();
        if (title != null && title.length() > 300) {
            title = title.substring(0, 300);
        }
        Conversation c = new Conversation(new Snapshot(id, customerId, orderId, title, ConversationStatus.NEW,
                opener.type(), opener.userId(), null, null, now, now.plus(firstResponseTarget), null, null, null, null,
                null, null, null, null, null, null, null, null, null, 0, null, null, null, continuedFromId, null, now,
                0));
        c.record(continuedFromId == null ? ConversationEventType.OPENED : ConversationEventType.CONTINUED, opener, now,
                null, null, null, continuedFromId == null ? null : "{\"continuedFrom\":\"" + continuedFromId + "\"}");
        return c;
    }

    /**
     * Routes a new conversation (16 §4.1 step 2): to {@code agentId} when one was found, to the queue otherwise. Done by
     * the system in the transaction that opened it; package 3's router decides who.
     */
    public void route(UUID agentId, Instant now) {
        requireStatus(ConversationStatus.NEW);
        if (agentId == null) {
            move(ConversationStatus.QUEUED, Actor.system(), now, null);
            record(ConversationEventType.QUEUED, Actor.system(), now, null, null, null, null);
            return;
        }
        giveTo(agentId, now);
        move(ConversationStatus.ASSIGNED, Actor.system(), now, null);
        record(ConversationEventType.ASSIGNED, Actor.system(), now, null, agentId, null, null);
    }

    /**
     * An agent takes a conversation nobody has (16 §4.1 step 2: "agent rảnh"). A new or queued one becomes ASSIGNED; a
     * reopened one that lost its agent keeps its status.
     *
     * @throws BusinessException {@code CHAT_ALREADY_ASSIGNED} when someone has it; {@code CHAT_CONVERSATION_ENDED} for
     *                           a closed one
     */
    public void claim(Actor agent, Instant now) {
        requireStaff(agent);
        requireNotClosed();
        if (assignedAgentId != null) {
            throw new BusinessException(ErrorCode.CHAT_ALREADY_ASSIGNED);
        }
        giveTo(agent.userId(), now);
        if (status == ConversationStatus.NEW || status == ConversationStatus.QUEUED) {
            move(ConversationStatus.ASSIGNED, agent, now, null);
        }
        record(ConversationEventType.CLAIMED, agent, now, null, agent.userId(), null, null);
    }

    /**
     * Hands the conversation to {@code agentId}: a supervisor assigns one nobody has (16 §2), the agent in charge or a
     * supervisor transfers one to a colleague (16 edge case "agent nghỉ": everything goes with it - context, notes,
     * history - because they are the conversation's, not the agent's). Whether {@code agentId} answers customers at
     * all is the caller's check.
     *
     * @throws BusinessException {@code CHAT_NOT_ASSIGNEE} for anyone else; {@code CHAT_CONVERSATION_ENDED} for a
     *                           closed conversation; {@code VALIDATION_FAILED} when it is already theirs
     */
    public void handTo(Actor by, UUID agentId, String note, Instant now) {
        requireStaff(by);
        requireNotClosed();
        Objects.requireNonNull(agentId, "agentId");
        if (agentId.equals(assignedAgentId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The conversation is already theirs");
        }
        UUID previous = assignedAgentId;
        if (previous == null) {
            if (!by.supervisor()) {
                throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE,
                        "Only a chat supervisor assigns a conversation; take it yourself instead");
            }
        } else if (!by.supervisor() && !previous.equals(by.userId())) {
            throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE);
        }
        giveTo(agentId, now);
        if (status == ConversationStatus.NEW || status == ConversationStatus.QUEUED) {
            move(ConversationStatus.ASSIGNED, by, now, null);
        }
        record(previous == null ? ConversationEventType.ASSIGNED : ConversationEventType.TRANSFERRED, by, now, null,
                agentId, note, previous == null ? null : "{\"previousAgent\":\"" + previous + "\"}");
    }

    /**
     * Numbers a message of the customer (16 §4.6 step 18, §5): one waiting on the customer is active again; a resolved
     * or auto-closed one within its reopen window is REOPENED.
     *
     * @return the message's sequence
     * @throws BusinessException {@code CHAT_CONVERSATION_ENDED} for a closed one, or one past its reopen window: the
     *                           customer starts a new conversation that continues it
     */
    public long acceptCustomerMessage(Actor customer, Instant now) {
        if (!customer.isCustomer()) {
            throw new IllegalArgumentException("Not the customer");
        }
        if (status == ConversationStatus.RESOLVED || status == ConversationStatus.AUTO_CLOSED) {
            if (!reopensAt(now)) {
                throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
            }
            move(ConversationStatus.REOPENED, customer, now, "The customer wrote again");
        } else if (status == ConversationStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        } else if (status == ConversationStatus.WAITING_ON_CUSTOMER) {
            move(ConversationStatus.ACTIVE, customer, now, "The customer answered");
        }
        lastCustomerMessageAt = now;
        return next(now);
    }

    /**
     * The customer comes back to a resolved or auto-closed conversation inside its reopen window (16 §5): REOPENED, for
     * the agent to take up again.
     *
     * @throws BusinessException {@code CHAT_CONVERSATION_ENDED} for one that is not, or no longer, reopenable
     */
    public void reopen(Actor customer, Instant now) {
        if (!customer.isCustomer()) {
            throw new IllegalArgumentException("Not the customer");
        }
        if (!reopensAt(now)) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
        move(ConversationStatus.REOPENED, customer, now, "The customer came back");
    }

    /**
     * Numbers an agent's message to the customer. Only the agent in charge answers (16 edge case "nhiều agent cùng
     * mở": the others watch), or a supervisor; the first answer stops the first-response clock (BR-09) and an assigned
     * or reopened conversation becomes ACTIVE.
     *
     * @return the message's sequence
     * @throws BusinessException {@code CHAT_NOT_ASSIGNEE}, or {@code CHAT_CONVERSATION_ENDED} for an ended one
     */
    public long acceptAgentMessage(Actor agent, Instant now) {
        requireStaff(agent);
        if (status.isEnded()) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
        requireInCharge(agent);
        if (status == ConversationStatus.ASSIGNED || status == ConversationStatus.REOPENED) {
            move(ConversationStatus.ACTIVE, agent, now, null);
        }
        if (firstRespondedAt == null) {
            firstRespondedAt = now;
            record(ConversationEventType.FIRST_RESPONSE, agent, now, null, null, null, null);
        }
        return next(now);
    }

    /**
     * Numbers an internal note: any staff member who may see the conversation writes one, at any time (16 §4.6 step 16:
     * colleagues answer the agent here). It moves nothing.
     */
    public long acceptInternalNote(Actor author, Instant now) {
        requireStaff(author);
        return next(now);
    }

    /** Numbers a card or a system line (package 3). A closed conversation takes none. */
    public long acceptSystemMessage(Instant now) {
        requireNotClosed();
        return next(now);
    }

    /**
     * The agent's (or supervisor's) moves of 16 §5: waiting on the customer or on another team, escalating (with the
     * reason, 16 §4.6 step 17), resolving (the reopen window starts) and coming back to ACTIVE. Only a supervisor
     * takes an escalated conversation further. NEW, QUEUED, ASSIGNED, REOPENED, AUTO_CLOSED and CLOSED are reached by
     * other methods, never asked for.
     *
     * @throws BusinessException {@code CHAT_TRANSITION_NOT_ALLOWED} for a move 16 §5 does not have from here, or that
     *                           is not asked for; {@code CHAT_NOT_ASSIGNEE}; {@code VALIDATION_FAILED} for an escalation
     *                           without a reason
     */
    public void changeStatus(Actor by, ConversationStatus target, String reason, Instant now, Duration reopenWindow) {
        requireStaff(by);
        if (target == null || target.isInitial() || target == ConversationStatus.REOPENED
                || target == ConversationStatus.AUTO_CLOSED || target == ConversationStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    "A conversation is not moved to " + target + " by hand");
        }
        if (!status.canMoveTo(target)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    status + " -> " + target + " is not a move of the conversation (16 §5)");
        }
        if (status == ConversationStatus.ESCALATED && !by.supervisor()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE,
                    "An escalated conversation is the chat supervisor's to take further");
        }
        requireInCharge(by);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (target == ConversationStatus.ESCALATED) {
            if (why == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the conversation is escalated");
            }
            escalationReason = why.length() > 1000 ? why.substring(0, 1000) : why;
        }
        if (target == ConversationStatus.RESOLVED) {
            resolvedAt = now;
            resolvedBy = by.userId();
            reopenUntil = now.plus(reopenWindow);
        }
        move(target, by, now, why);
    }

    /**
     * The customer stayed silent past the waiting time (package 3's job): AUTO_CLOSED, reopenable like a resolved
     * conversation until {@code reopenWindow} from now.
     */
    public void autoClose(Instant now, Duration reopenWindow) {
        requireStatus(ConversationStatus.WAITING_ON_CUSTOMER);
        autoClosedAt = now;
        reopenUntil = now.plus(reopenWindow);
        move(ConversationStatus.AUTO_CLOSED, Actor.system(), now, "The customer did not answer");
    }

    /** The reopen window passed (package 3's job): CLOSED for good. */
    public void close(Instant now) {
        if (status != ConversationStatus.RESOLVED && status != ConversationStatus.AUTO_CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED, status + " -> CLOSED (16 §5)");
        }
        if (reopensAt(now)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED, "The reopen window is still open");
        }
        closedAt = now;
        move(ConversationStatus.CLOSED, Actor.system(), now, null);
    }

    /** Logs that a context was attached (by the opener, an agent, or the system for a card). */
    public void contextAttached(Actor by, UUID contextId, Instant now) {
        record(ConversationEventType.CONTEXT_ATTACHED, by, now, contextId, null, null, null);
    }

    /** Logs a consequential action on a message (a note written, a card sent or acted on...). */
    public void logMessageAction(ConversationEventType type, Actor by, UUID messageId, UUID contextId, String note,
                                 String detailJson, Instant now) {
        LogEntry entry = new LogEntry(Identifiers.newId(), id, type, by.type(), by.userId(), now, null, null,
                contextId, messageId, null, note, detailJson);
        log.add(entry);
    }

    /** Whether the customer's word at {@code now} still reopens it: resolved or auto-closed, inside the window. */
    public boolean reopensAt(Instant now) {
        return (status == ConversationStatus.RESOLVED || status == ConversationStatus.AUTO_CLOSED)
                && reopenUntil != null && now.isBefore(reopenUntil);
    }

    /**
     * Whether a customer reopening it gets a new conversation rather than this one: closed, or ended past its window.
     */
    public boolean continuesAsNewAt(Instant now) {
        return status == ConversationStatus.CLOSED || (status.isEnded() && !reopensAt(now));
    }

    /** Whether the first answer is late: none came, and it was due before {@code now} (BR-09). */
    public boolean firstResponseOverdueAt(Instant now) {
        return firstRespondedAt == null && firstResponseDueAt != null && !status.isEnded()
                && firstResponseDueAt.isBefore(now);
    }

    /** The log lines written since the last save, in order, for the repository to append. */
    public List<LogEntry> pullLog() {
        List<LogEntry> copy = List.copyOf(log);
        log.clear();
        return copy;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, customerId, orderId, subject, status, openedByType, openedBy, assignedAgentId,
                assignedAt, statusChangedAt, firstResponseDueAt, firstRespondedAt, firstResponseAlertedAt,
                resolutionDueAt, resolutionAlertedAt, escalationReason, escalatedToId, resolvedAt, resolvedBy,
                autoClosedAt, closedAt, reopenUntil, lastCustomerMessageAt, lastMessageAt, lastMessageSeq, csatScore,
                csatComment, csatSubmittedAt, continuedFromId, mergedIntoId, createdAt, version);
    }

    private void move(ConversationStatus target, Actor by, Instant now, String note) {
        if (!status.canMoveTo(target)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    status + " -> " + target + " is not a move of the conversation (16 §5)");
        }
        ConversationStatus from = status;
        status = target;
        statusChangedAt = now;
        log.add(new LogEntry(Identifiers.newId(), id, ConversationEventType.STATUS_CHANGED, by.type(), by.userId(), now,
                from, target, null, null, null, note, null));
    }

    private void record(ConversationEventType type, Actor by, Instant now, UUID contextId, UUID target, String note,
                        String detailJson) {
        log.add(new LogEntry(Identifiers.newId(), id, type, by.type(), by.userId(), now, null, null, contextId, null,
                target, note, detailJson));
    }

    private void giveTo(UUID agentId, Instant now) {
        assignedAgentId = agentId;
        assignedAt = now;
    }

    private long next(Instant now) {
        lastMessageSeq++;
        lastMessageAt = now;
        return lastMessageSeq;
    }

    private void requireInCharge(Actor agent) {
        if (agent.supervisor()) {
            return;
        }
        if (assignedAgentId == null || !assignedAgentId.equals(agent.userId())) {
            throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE);
        }
    }

    private void requireStaff(Actor actor) {
        if (!actor.isStaff()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This is the staff's to do");
        }
    }

    private void requireNotClosed() {
        if (status == ConversationStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
    }

    private void requireStatus(ConversationStatus expected) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    "Expected a " + expected + " conversation, it is " + status);
        }
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public UUID orderId() { return orderId; }
    public String subject() { return subject; }
    public ConversationStatus status() { return status; }
    public UUID assignedAgentId() { return assignedAgentId; }
    public Instant firstResponseDueAt() { return firstResponseDueAt; }
    public Instant firstRespondedAt() { return firstRespondedAt; }
    public Instant reopenUntil() { return reopenUntil; }
    public long lastMessageSeq() { return lastMessageSeq; }
    public Instant lastMessageAt() { return lastMessageAt; }
    public UUID continuedFromId() { return continuedFromId; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
