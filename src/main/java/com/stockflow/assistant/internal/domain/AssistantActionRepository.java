package com.stockflow.assistant.internal.domain;

import com.stockflow.common.api.PageResponse;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where {@link AssistantAction}s are kept. Every read names the owner: a suggestion is only ever its owner's. */
public interface AssistantActionRepository {

    /** Inserts a new one, or writes the decision of one read with {@link #findForUpdate}. */
    AssistantAction save(AssistantAction action);

    /**
     * Inserts the suggestions an answer made, in the transaction that stores the answer. Each names its answer's
     * message ({@code fk_action_message}), which that transaction has written but may not yet have sent to the
     * database; the implementation sends it first.
     */
    void suggestAll(List<AssistantAction> actions);

    /**
     * Marks SUPERSEDED the suggestions of {@code ownerUserId} with this kind and key that are still open at
     * {@code now} and whose every line is one of {@code skus}: a newer one that orders all of it replaces them. One
     * with a line the newer one lacks stays open, as does a confirmed, dismissed or expired one.
     *
     * @param skus the newer suggestion's SKUs, upper case
     * @return how many were superseded
     */
    int supersedeCovered(UUID ownerUserId, AssistantAction.Kind kind, String proposalKey, Collection<String> skus,
                         Instant now);

    /**
     * Whether {@code ownerUserId} confirmed another suggestion of this kind and key since {@code since}: for a
     * receipt, another draft of the same purchase order's delivery.
     */
    boolean confirmedSince(UUID ownerUserId, AssistantAction.Kind kind, String proposalKey, Instant since,
                           UUID excluding);

    /** {@code ownerUserId}'s action, locked until the transaction ends; empty when there is none of theirs. */
    Optional<AssistantAction> findForUpdate(UUID actionId, UUID ownerUserId);

    Optional<AssistantAction> find(UUID actionId, UUID ownerUserId);

    /** {@code ownerUserId}'s actions, newest first; with {@code openOnly}, those still open at {@code now}. */
    PageResponse<AssistantAction> mine(UUID ownerUserId, boolean openOnly, Instant now, int page, int size);

    /** The suggestions the given answers made, of {@code ownerUserId}. */
    List<AssistantAction> ofMessages(Collection<UUID> messageIds, UUID ownerUserId);

    /** Deletes photos' suggestions created before {@code cutoff}, at most {@code limit}; an answer's go with it. */
    int deleteStandaloneBefore(Instant cutoff, int limit);
}
