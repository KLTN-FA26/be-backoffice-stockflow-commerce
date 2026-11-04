package com.stockflow.assistant.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the assistant suggested its user do, and what they decided (ADR-0011, design §8.2): a purchase-order draft
 * an answer proposed, a goods-receipt draft read from a photo. The model never writes; this is the suggestion, kept so
 * that the user can turn it into the real document, once, with what they changed, and so that nobody can confirm a
 * suggestion that was not theirs or that has gone stale.
 *
 * <p><b>Expired is read, not stored.</b> A suggestion is SUGGESTED until it is decided; past {@code expiresAt} it reads
 * {@link Status#EXPIRED} and can no longer be confirmed, with nothing written to say so. Dismissing an expired one is
 * allowed: it only clears it from the list.</p>
 */
public final class AssistantAction {

    /** How long a suggestion stays open: the quantities and the stock behind them are a day's. */
    public static final Duration OPEN_FOR = Duration.ofHours(24);

    /** What would be created. The same names as {@code ck_action_kind}. */
    public enum Kind { PURCHASE_ORDER_DRAFT, GOODS_RECEIPT_DRAFT }

    /**
     * {@code ck_action_status} holds the first four; EXPIRED is how a SUGGESTED one past its time reads. SUPERSEDED: a
     * newer suggestion of the same owner and {@link #proposalKey} replaced it while it was open.
     */
    public enum Status { SUGGESTED, CONFIRMED, DISMISSED, SUPERSEDED, EXPIRED }

    private final UUID id;
    private final UUID ownerUserId;
    private final UUID conversationId;
    private final UUID messageId;
    private final Kind kind;
    private final String proposalKey;
    private final String suggested;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Status status;
    private String submitted;
    private Instant decidedAt;
    private UUID resultId;
    private String resultNumber;

    /** As stored; {@link #suggest} makes a new one. */
    public AssistantAction(UUID id, UUID ownerUserId, UUID conversationId, UUID messageId, Kind kind,
                           String proposalKey, String suggested, Status status, String submitted, Instant createdAt,
                           Instant expiresAt, Instant decidedAt, UUID resultId, String resultNumber) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.suggested = Objects.requireNonNull(suggested, "suggested");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (status == null || status == Status.EXPIRED) {
            throw new IllegalArgumentException("a stored action is SUGGESTED, CONFIRMED, DISMISSED or SUPERSEDED");
        }
        if ((conversationId == null) != (messageId == null)) {
            throw new IllegalArgumentException("an answer's suggestion names its conversation and answer; a photo's none");
        }
        if (proposalKey == null || !proposalKey.startsWith(kind.name() + ":")
                || proposalKey.length() == kind.name().length() + 1) {
            throw new IllegalArgumentException("a suggestion names what it proposes, after its kind: " + proposalKey);
        }
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.proposalKey = proposalKey;
        this.status = status;
        this.submitted = submitted;
        this.decidedAt = decidedAt;
        this.resultId = resultId;
        this.resultNumber = resultNumber;
    }

    /**
     * A new suggestion, open for {@link #OPEN_FOR}.
     *
     * @param conversationId with {@code messageId}: the answer that suggested it; both null for a draft read from a
     *                       photo
     * @param proposalKey    what it proposes: the proposal's type and supplier, or the kind and purchase order of a
     *                       receipt
     */
    public static AssistantAction suggest(UUID id, UUID ownerUserId, UUID conversationId, UUID messageId, Kind kind,
                                          String proposalKey, String suggested, Instant now) {
        return new AssistantAction(id, ownerUserId, conversationId, messageId, kind, proposalKey, suggested,
                Status.SUGGESTED, null, now, now.plus(OPEN_FOR), null, null, null);
    }

    /** The status as of {@code now}: an open suggestion past its time is EXPIRED. */
    public Status statusAt(Instant now) {
        return status == Status.SUGGESTED && !now.isBefore(expiresAt) ? Status.EXPIRED : status;
    }

    /** Whether {@code userId} may decide it: only its owner, whatever their scope on the documents. */
    public boolean ownedBy(UUID userId) {
        return ownerUserId.equals(userId);
    }

    /**
     * Refuses a decision on a suggestion that is no longer open. A CONFIRMED one is not refused here: the caller
     * returns the document it already created ({@link #resultId}).
     *
     * @throws BusinessException {@code ASSISTANT_ACTION_EXPIRED}, {@code ASSISTANT_ACTION_CLOSED}
     */
    public void requireOpen(Instant now) {
        Status current = statusAt(now);
        if (current == Status.EXPIRED) {
            throw new BusinessException(ErrorCode.ASSISTANT_ACTION_EXPIRED,
                    "This suggestion expired at " + expiresAt + "; ask the assistant again");
        }
        if (current == Status.DISMISSED) {
            throw new BusinessException(ErrorCode.ASSISTANT_ACTION_CLOSED, "This suggestion was dismissed");
        }
        if (current == Status.SUPERSEDED) {
            throw new BusinessException(ErrorCode.ASSISTANT_ACTION_CLOSED,
                    "A newer suggestion replaced this one; confirm that one instead");
        }
    }

    /** The document was created from it, as {@code submitted} says. */
    public void confirm(String submitted, UUID resultId, String resultNumber, Instant now) {
        if (statusAt(now) != Status.SUGGESTED) {
            throw new IllegalStateException("only an open suggestion is confirmed, not " + statusAt(now));
        }
        this.status = Status.CONFIRMED;
        this.submitted = Objects.requireNonNull(submitted, "submitted");
        this.resultId = Objects.requireNonNull(resultId, "resultId");
        this.resultNumber = Objects.requireNonNull(resultNumber, "resultNumber");
        this.decidedAt = now;
    }

    /**
     * Its owner does not want it. Open or expired, it is dismissed; dismissed or superseded already, nothing changes.
     *
     * @throws BusinessException {@code ASSISTANT_ACTION_CLOSED} when it was confirmed: the document exists
     */
    public void dismiss(Instant now) {
        if (status == Status.CONFIRMED) {
            throw new BusinessException(ErrorCode.ASSISTANT_ACTION_CLOSED,
                    "This suggestion was confirmed as " + resultNumber + "; cancel that document on its screen");
        }
        if (status == Status.SUGGESTED) {
            status = Status.DISMISSED;
            decidedAt = now;
        }
    }

    public UUID id() { return id; }
    public UUID ownerUserId() { return ownerUserId; }
    public UUID conversationId() { return conversationId; }
    public UUID messageId() { return messageId; }
    public Kind kind() { return kind; }
    public String proposalKey() { return proposalKey; }
    public String suggested() { return suggested; }
    public String submitted() { return submitted; }
    public Status storedStatus() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant decidedAt() { return decidedAt; }
    public UUID resultId() { return resultId; }
    public String resultNumber() { return resultNumber; }
}
