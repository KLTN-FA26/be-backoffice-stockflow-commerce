package com.stockflow.chat.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * <p>The two clocks of BR-09 run from the moment the customer asks something (the caller works out their due times in
 * business hours, {@link Due}): the first answer's stops at the first answer, the resolution's at RESOLVED. A
 * conversation the customer reopens is a new question to resolve, so its resolution clock starts again; the first
 * answer was given once and is not owed again.</p>
 *
 * <p>Messages are not loaded with it. A message is numbered here ({@code accept*Message} returns its sequence) under
 * the conversation's row lock, which is what makes the numbering gapless and a retried send find its first attempt.</p>
 */
public final class Conversation extends AggregateRoot {

    /** {@code chat.conversation.csat_comment}. */
    public static final int MAX_CSAT_COMMENT = 2000;

    /** The log's note on a conversation put back in the queue (BR-S08). A stored note, like every log note: English. */
    public static final String REQUEUED_NOTE = "The agent's account was locked or disabled: back to the queue";

    /**
     * When a customer asking at a moment is due their first answer and the resolution of what they asked (BR-09),
     * worked out by the caller in business hours. Either may be null: that clock does not start.
     */
    public record Due(Instant firstResponse, Instant resolution) {

        /** No clock starts: a conversation the system opens for a card nobody asked a question about. */
        public static final Due NONE = new Due(null, null);
    }

    /** Everything the aggregate holds, for the repository to store and restore. */
    public record Snapshot(UUID id, UUID customerId, UUID orderId, String subject, ConversationStatus status,
                           SenderType openedByType, UUID openedBy, UUID assignedAgentId, Instant assignedAt,
                           Instant statusChangedAt, Instant firstResponseDueAt, Instant firstRespondedAt,
                           Instant firstResponseAlertedAt, Instant resolutionDueAt, Instant resolutionAlertedAt,
                           String escalationReason, UUID escalatedToId, Instant resolvedAt, UUID resolvedBy,
                           Instant autoClosedAt, Instant closedAt, Instant reopenUntil,
                           Instant lastCustomerMessageAt, Instant lastMessageAt, long lastMessageSeq,
                           Integer csatScore, String csatComment, Instant csatSubmittedAt, UUID continuedFromId,
                           UUID mergedIntoId, Instant requeuedAt, Instant createdAt, long version) {
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
    private Instant requeuedAt;
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
        this.requeuedAt = s.requeuedAt();
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
        return open(id, customerId, subject, orderId, opener, continuedFromId, now, now.plus(firstResponseTarget));
    }

    /**
     * A new conversation whose first answer is due at {@code firstResponseDueAt} - worked out in business hours by the
     * caller (16 §4.1 step 3) - or not due at all yet: a conversation the system opens to carry a card nobody asked a
     * question about (an invoice issued, a parcel shipped) starts its clock when the customer first writes
     * ({@link #acceptCustomerMessage(Actor, Instant, Instant)}), so it is never "overdue" for an answer to nothing.
     */
    public static Conversation open(UUID id, UUID customerId, String subject, UUID orderId, Actor opener,
                                    UUID continuedFromId, Instant now, Instant firstResponseDueAt) {
        return open(id, customerId, subject, orderId, opener, continuedFromId, now, new Due(firstResponseDueAt, null));
    }

    /**
     * A new conversation whose first answer and resolution are due as {@code due} says (16 §4.1 step 3, BR-09); with
     * {@link Due#NONE} neither clock runs until the customer first writes.
     */
    public static Conversation open(UUID id, UUID customerId, String subject, UUID orderId, Actor opener,
                                    UUID continuedFromId, Instant now, Due due) {
        if (opener.isCustomer() || opener.isStaff()) {
            Objects.requireNonNull(opener.userId(), "opener");
        }
        String title = subject == null || subject.isBlank() ? null : subject.strip();
        if (title != null && title.length() > 300) {
            title = title.substring(0, 300);
        }
        Conversation c = new Conversation(new Snapshot(id, customerId, orderId, title, ConversationStatus.NEW,
                opener.type(), opener.userId(), null, null, now, due.firstResponse(), null, null, due.resolution(),
                null, null, null, null, null, null, null, null, null, null, 0, null, null, null, continuedFromId, null,
                null, now, 0));
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
     * reopened one that lost its agent keeps its status. Taking one that is already the caller's changes nothing: a
     * retried claim, or one that lost the race to the owner change it caused (BR-S05: the claimer's other conversations
     * of the customer follow them after commit).
     *
     * @return whether the conversation was taken now; false when it was the caller's already
     * @throws BusinessException {@code CHAT_ALREADY_ASSIGNED} when someone else has it; {@code CHAT_CONVERSATION_ENDED}
     *                           for a closed one
     */
    public boolean claim(Actor agent, Instant now) {
        requireStaff(agent);
        requireNotClosed();
        if (agent.userId().equals(assignedAgentId)) {
            return false;
        }
        if (assignedAgentId != null) {
            throw new BusinessException(ErrorCode.CHAT_ALREADY_ASSIGNED);
        }
        giveTo(agent.userId(), now);
        if (status == ConversationStatus.NEW || status == ConversationStatus.QUEUED) {
            move(ConversationStatus.ASSIGNED, agent, now, null);
        }
        record(ConversationEventType.CLAIMED, agent, now, null, agent.userId(), null, null);
        return true;
    }

    /**
     * The customer's Sales owner changed (BR-S06, 16 BR-14): the system hands the conversation to the new owner, with
     * the change written on its log line. It keeps its status (16 §5: only the agent changes); a queued one is ASSIGNED,
     * since a conversation with an agent is never in the queue. Not done to an ended one, nor to one that is already
     * the owner's.
     *
     * @param note       why, for the log line (the change's reason); may be null
     * @param detailJson what the log line keeps besides (the change's owners and reason); may be null
     * @return whether it moved
     */
    public boolean followOwner(UUID ownerId, String note, String detailJson, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (status.isEnded() || ownerId.equals(assignedAgentId)) {
            return false;
        }
        UUID previous = assignedAgentId;
        giveTo(ownerId, now);
        if (status == ConversationStatus.NEW || status == ConversationStatus.QUEUED) {
            move(ConversationStatus.ASSIGNED, Actor.system(), now, null);
        }
        record(previous == null ? ConversationEventType.ASSIGNED : ConversationEventType.TRANSFERRED, Actor.system(),
                now, null, ownerId, note, detailJson);
        return true;
    }

    /**
     * The account of the agent on it was locked or disabled (BR-S08, 16 BR-14): an open conversation goes back to the
     * queue whatever its state, flagged so it is shown first ({@link #requeuedAt}), with its contexts, notes and log -
     * they are the conversation's. Whoever takes it next clears the flag. Nothing happens to an ended conversation (it
     * has nobody to wait for) nor to one that is not {@code agentId}'s: a redelivered event finds nothing left to do.
     *
     * @return whether it went back to the queue
     */
    public boolean requeueFrom(UUID agentId, Instant now) {
        if (agentId == null || !agentId.equals(assignedAgentId) || status.isEnded()) {
            return false;
        }
        assignedAgentId = null;
        assignedAt = null;
        requeuedAt = now;
        move(ConversationStatus.QUEUED, Actor.system(), now, REQUEUED_NOTE);
        record(ConversationEventType.QUEUED, Actor.system(), now, null, null, REQUEUED_NOTE,
                "{\"previousAgent\":\"" + agentId + "\",\"reason\":\"AGENT_DEACTIVATED\"}");
        return true;
    }

    /**
     * The customer reopened the conversation (16 §5): it goes back to whoever looks after the customer now, not to
     * whoever had it when it ended. An ended conversation is left out when the owner changes ({@link #followOwner})
     * and when its agent's account stops working ({@link #requeueFrom}), so the agent it kept may be someone who no
     * longer looks after the customer - a former owner after a transfer - or nobody at all - a locked account. Done by
     * the system, after the reopening is stored (the database judges one move at a time):
     * <ul>
     *   <li>the customer's acting salesperson (owner or cover, who answers customers) gets it, keeping REOPENED;</li>
     *   <li>with none, an agent who no longer answers customers gives it back to the queue, flagged first as
     *       {@link #requeueFrom} does (BR-S08);</li>
     *   <li>with none, an agent who still answers keeps it: the customer has nobody else (a manager who served a
     *       customer without an owner, say), and the queue would take it from the one person who knows it.</li>
     * </ul>
     *
     * @param actingAgent    the customer's acting salesperson who answers customers; null when there is none
     * @param agentAnswers   whether the agent on it still answers customers
     * @return whether it changed hands
     */
    public boolean rerouteOnReopen(UUID actingAgent, boolean agentAnswers, Instant now) {
        if (status != ConversationStatus.REOPENED) {
            return false;
        }
        UUID previous = assignedAgentId;
        if (actingAgent != null) {
            if (actingAgent.equals(previous)) {
                return false;
            }
            giveTo(actingAgent, now);
            record(previous == null ? ConversationEventType.ASSIGNED : ConversationEventType.TRANSFERRED,
                    Actor.system(), now, null, actingAgent, REOPEN_REROUTED_NOTE, previous == null ? null
                            : "{\"previousAgent\":\"" + previous + "\",\"reason\":\"REOPENED\"}");
            return true;
        }
        return previous != null && !agentAnswers && requeueFrom(previous, now);
    }

    /** The log's note on a reopened conversation handed to whoever looks after the customer now. English, stored. */
    public static final String REOPEN_REROUTED_NOTE = "Reopened: handed to whoever looks after the customer now";

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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The conversation is already theirs").localized("refusal.chat.alreadyTheirs");
        }
        UUID previous = assignedAgentId;
        if (previous == null) {
            if (!by.supervisor()) {
                throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE,
                        "Only a chat supervisor assigns a conversation; take it yourself instead").localized("refusal.chat.supervisorAssigns");
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
        return acceptCustomerMessage(customer, now, Due.NONE);
    }

    /**
     * {@link #acceptCustomerMessage(Actor, Instant)}, starting the first-response clock at {@code dueIfNotRunning} when
     * none runs yet: a conversation the system opened for a card waits for nobody until the customer asks something.
     */
    public long acceptCustomerMessage(Actor customer, Instant now, Instant dueIfNotRunning) {
        return acceptCustomerMessage(customer, now, new Due(dueIfNotRunning, null));
    }

    /**
     * {@link #acceptCustomerMessage(Actor, Instant)}, starting each BR-09 clock that does not run yet at what
     * {@code due} says - and the resolution clock again when this message reopens a resolved or auto-closed
     * conversation: what the customer asks now is a new thing to resolve.
     */
    public long acceptCustomerMessage(Actor customer, Instant now, Due due) {
        if (!customer.isCustomer()) {
            throw new IllegalArgumentException("Not the customer");
        }
        boolean reopened = false;
        if (status == ConversationStatus.RESOLVED || status == ConversationStatus.AUTO_CLOSED) {
            if (!reopensAt(now)) {
                throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
            }
            move(ConversationStatus.REOPENED, customer, now, "The customer wrote again");
            reopened = true;
        } else if (status == ConversationStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        } else if (status == ConversationStatus.WAITING_ON_CUSTOMER) {
            move(ConversationStatus.ACTIVE, customer, now, "The customer answered");
        }
        lastCustomerMessageAt = now;
        if (firstRespondedAt == null && firstResponseDueAt == null && due.firstResponse() != null) {
            firstResponseDueAt = due.firstResponse();
        }
        startResolutionClock(due.resolution(), reopened);
        return next(now);
    }

    /**
     * The customer comes back to a resolved or auto-closed conversation inside its reopen window (16 §5): REOPENED, for
     * the agent to take up again.
     *
     * @throws BusinessException {@code CHAT_CONVERSATION_ENDED} for one that is not, or no longer, reopenable
     */
    public void reopen(Actor customer, Instant now) {
        reopen(customer, now, null);
    }

    /**
     * {@link #reopen(Actor, Instant)}, its resolution due again at {@code resolutionDue} (BR-09): the customer came
     * back because something is not resolved after all.
     */
    public void reopen(Actor customer, Instant now, Instant resolutionDue) {
        if (!customer.isCustomer()) {
            throw new IllegalArgumentException("Not the customer");
        }
        if (!reopensAt(now)) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
        move(ConversationStatus.REOPENED, customer, now, "The customer came back");
        startResolutionClock(resolutionDue, true);
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
        if (!askedForByHand(target)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    "A conversation is not moved to " + target + " by hand").localized("refusal.chat.notByHand", target);
        }
        if (!status.canMoveTo(target)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    status + " -> " + target + " is not a move of the conversation (16 §5)")
                    .localized("refusal.chat.notAMove", status, target);
        }
        if (status == ConversationStatus.ESCALATED && !by.supervisor()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE,
                    "An escalated conversation is the chat supervisor's to take further").localized("refusal.chat.escalatedSupervisor");
        }
        requireInCharge(by);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (target == ConversationStatus.ESCALATED) {
            if (why == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the conversation is escalated").localized("refusal.chat.escalationReason");
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
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED, "The reopen window is still open").localized("refusal.chat.reopenWindowOpen");
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

    /**
     * The first answer is late and the supervisors have not been told yet (BR-09, the SLA job): marks them told and
     * logs FIRST_RESPONSE_OVERDUE, once - the job runs every minute, the alert goes out once per conversation.
     *
     * @return whether they are to be told now
     */
    public boolean alertFirstResponseOverdue(Instant now) {
        if (!firstResponseOverdueAt(now) || firstResponseAlertedAt != null) {
            return false;
        }
        firstResponseAlertedAt = now;
        record(ConversationEventType.FIRST_RESPONSE_OVERDUE, Actor.system(), now, null, assignedAgentId, null,
                "{\"dueAt\":\"" + firstResponseDueAt + "\"}");
        return true;
    }

    /**
     * The conversation is not resolved by its resolution target and the supervisors have not been told yet (BR-09, the
     * SLA job): marks them told and logs RESOLUTION_OVERDUE, once per run of the clock - a reopened conversation's new
     * clock may alert again.
     *
     * @return whether they are to be told now
     */
    public boolean alertResolutionOverdue(Instant now) {
        if (!resolutionOverdueAt(now) || resolutionAlertedAt != null) {
            return false;
        }
        resolutionAlertedAt = now;
        record(ConversationEventType.RESOLUTION_OVERDUE, Actor.system(), now, null, assignedAgentId, null,
                "{\"dueAt\":\"" + resolutionDueAt + "\"}");
        return true;
    }

    /** Whether the resolution is late: the conversation goes on and was to be resolved before {@code now} (BR-09). */
    public boolean resolutionOverdueAt(Instant now) {
        return resolutionDueAt != null && !status.isEnded() && resolutionDueAt.isBefore(now);
    }

    /**
     * The customer rates how the conversation went (16 §4.6 step 18, BR-09 CSAT; decided 2026-11-13: 1 to 5 stars and an
     * optional comment), once, while it is resolved: a rating asked of a conversation still going on would rate half of
     * it, and a second rating would let one conversation count twice.
     *
     * @throws BusinessException {@code CHAT_CSAT_NOT_OPEN} unless it is resolved; {@code CHAT_CSAT_ALREADY_GIVEN};
     *                           {@code VALIDATION_FAILED} for a score outside 1-5
     */
    public void rate(Actor customer, int score, String comment, Instant now) {
        if (!customer.isCustomer()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the customer rates the conversation").localized("refusal.chat.customerRates");
        }
        if (csatSubmittedAt != null) {
            throw new BusinessException(ErrorCode.CHAT_CSAT_ALREADY_GIVEN);
        }
        if (status != ConversationStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.CHAT_CSAT_NOT_OPEN);
        }
        if (score < 1 || score > 5) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rating is 1 to 5 stars").localized("refusal.chat.ratingRange");
        }
        String said = comment == null || comment.isBlank() ? null : SensitiveData.mask(comment.strip());
        if (said != null && said.length() > MAX_CSAT_COMMENT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A comment is at most " + MAX_CSAT_COMMENT + " characters").localized("refusal.chat.commentTooLong", MAX_CSAT_COMMENT);
        }
        csatScore = score;
        csatComment = said;
        csatSubmittedAt = now;
        record(ConversationEventType.CSAT_SUBMITTED, customer, now, null, null, null, "{\"score\":" + score + "}");
    }

    /**
     * Whether {@code agent} may act for the conversation towards the customer - draw up a quote from its card, send
     * transfer details: the same rule as answering ({@link #acceptAgentMessage}), the agent in charge or a supervisor.
     *
     * @throws BusinessException {@code CHAT_NOT_ASSIGNEE}; {@code CHAT_CONVERSATION_ENDED} for a closed one
     */
    public void requireInCharge(Actor agent, boolean evenIfEnded) {
        requireStaff(agent);
        if (status == ConversationStatus.CLOSED || (!evenIfEnded && status.isEnded())) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
        requireInCharge(agent);
    }

    /**
     * Whether {@code agent} may act for the conversation towards the customer now - answer it, send transfer details,
     * act on a card: what {@link #acceptAgentMessage} and {@link #requireInCharge(Actor, boolean)} (not even if ended)
     * accept. Asked by the views so a client never offers what these would refuse with {@code CHAT_NOT_ASSIGNEE}.
     */
    public boolean answerableBy(Actor agent) {
        return agent.isStaff() && !status.isEnded() && inCharge(agent);
    }

    /** Whether {@code agent} may take the conversation now: what {@link #claim} accepts. */
    public boolean claimableBy(Actor agent) {
        return agent.isStaff() && status != ConversationStatus.CLOSED && assignedAgentId == null;
    }

    /**
     * Whether {@code by} may hand the conversation to someone: what {@link #handTo} accepts of the person asking - a
     * supervisor always, the agent in charge their own. Whom it goes to is checked when they ask.
     */
    public boolean assignableBy(Actor by) {
        return by.isStaff() && status != ConversationStatus.CLOSED && inCharge(by);
    }

    /**
     * The statuses {@code by} may ask for from here ({@link #changeStatus}): the moves of 16 §5 that are asked for by
     * hand, and none unless they are in charge - an escalated conversation only for a supervisor. Empty for anyone
     * else, so the views offer exactly what {@code changeStatus} accepts.
     */
    public Set<ConversationStatus> movesOpenTo(Actor by) {
        if (!by.isStaff() || (status == ConversationStatus.ESCALATED && !by.supervisor()) || !inCharge(by)) {
            return Set.of();
        }
        Set<ConversationStatus> open = EnumSet.noneOf(ConversationStatus.class);
        for (ConversationStatus target : status.next()) {
            if (askedForByHand(target)) {
                open.add(target);
            }
        }
        return Set.copyOf(open);
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
                csatComment, csatSubmittedAt, continuedFromId, mergedIntoId, requeuedAt, createdAt, version);
    }

    private void move(ConversationStatus target, Actor by, Instant now, String note) {
        if (!status.canMoveTo(target)) {
            throw new BusinessException(ErrorCode.CHAT_TRANSITION_NOT_ALLOWED,
                    status + " -> " + target + " is not a move of the conversation (16 §5)")
                    .localized("refusal.chat.notAMove", status, target);
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

    /**
     * Starts the resolution clock at {@code due} unless one runs already; {@code again} restarts it, the earlier alert
     * forgotten with it. A null {@code due} starts nothing.
     */
    private void startResolutionClock(Instant due, boolean again) {
        if (due != null && (resolutionDueAt == null || again)) {
            resolutionDueAt = due;
            resolutionAlertedAt = null;
        }
    }

    /** Someone has the conversation now: the queue's flag (BR-S08) means nothing any more. */
    private void giveTo(UUID agentId, Instant now) {
        assignedAgentId = agentId;
        assignedAt = now;
        requeuedAt = null;
    }

    private long next(Instant now) {
        lastMessageSeq++;
        lastMessageAt = now;
        return lastMessageSeq;
    }

    private void requireInCharge(Actor agent) {
        if (!inCharge(agent)) {
            throw new BusinessException(ErrorCode.CHAT_NOT_ASSIGNEE);
        }
    }

    /** The agent in charge, or a supervisor: who answers the customer and moves the conversation along. */
    private boolean inCharge(Actor agent) {
        return agent.supervisor() || (assignedAgentId != null && assignedAgentId.equals(agent.userId()));
    }

    /** NEW, QUEUED, ASSIGNED, REOPENED, AUTO_CLOSED and CLOSED are reached by other methods, never asked for. */
    private static boolean askedForByHand(ConversationStatus target) {
        return target != null && !target.isInitial() && target != ConversationStatus.REOPENED
                && target != ConversationStatus.AUTO_CLOSED && target != ConversationStatus.CLOSED;
    }

    private void requireStaff(Actor actor) {
        if (!actor.isStaff()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This is the staff's to do").localized("refusal.chat.staffAction");
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
                    "Expected a " + expected + " conversation, it is " + status).localized("refusal.chat.expectedStatus", expected, status);
        }
    }

    public Instant lastCustomerMessageAt() { return lastCustomerMessageAt; }
    public Instant statusChangedAt() { return statusChangedAt; }
    public Instant firstResponseAlertedAt() { return firstResponseAlertedAt; }
    public Instant resolvedAt() { return resolvedAt; }
    public Integer csatScore() { return csatScore; }
    public String csatComment() { return csatComment; }
    public Instant csatSubmittedAt() { return csatSubmittedAt; }
    public SenderType openedByType() { return openedByType; }
    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public UUID orderId() { return orderId; }
    public String subject() { return subject; }
    public ConversationStatus status() { return status; }
    public UUID assignedAgentId() { return assignedAgentId; }
    public Instant firstResponseDueAt() { return firstResponseDueAt; }
    public Instant resolutionDueAt() { return resolutionDueAt; }
    public Instant resolutionAlertedAt() { return resolutionAlertedAt; }
    public Instant firstRespondedAt() { return firstRespondedAt; }
    public Instant reopenUntil() { return reopenUntil; }
    public long lastMessageSeq() { return lastMessageSeq; }
    public Instant lastMessageAt() { return lastMessageAt; }
    public UUID continuedFromId() { return continuedFromId; }
    /** When it went back to the queue because its agent's account stopped working (BR-S08); null otherwise. */
    public Instant requeuedAt() { return requeuedAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
