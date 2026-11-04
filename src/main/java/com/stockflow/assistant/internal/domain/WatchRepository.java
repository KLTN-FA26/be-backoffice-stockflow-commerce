package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the assistant's watch has seen on each user's lists ({@code assistant.watch_item}, design §9.1), and how it
 * looks at each list ({@code assistant.watch_state}). Every call joins the caller's transaction.
 */
public interface WatchRepository {

    /**
     * An item the watch saw; {@code key} is its own codes, as its list shows them.
     *
     * @param analysed whether a proactive conversation was opened about it already: it is never asked about twice
     */
    record SeenItem(UUID id, String section, String key, Instant firstSeenAt, boolean analysed) {
    }

    /**
     * How a section is looked at for a user.
     *
     * @param view         a digest of what the user could see when the watch began: their scope and warehouses. Another
     *                     view is another list, whose items are not news to compare
     * @param partialSince since when the list has been longer than one read; null while it is read whole
     */
    record State(String view, Instant startedAt, Instant lastLookedAt, Instant partialSince) {
    }

    Optional<State> state(UUID ownerUserId, String section);

    /** Begins looking at the section anew: its items are dropped and the look is a first look, a baseline. */
    void restart(UUID ownerUserId, String section, String view, Instant at);

    /** @param partialSince since when the list is read only in part; null when it was read whole */
    void looked(UUID ownerUserId, String section, Instant at, Instant partialSince);

    /** Stops looking at the section for the user: the state goes, and its items with it. */
    void drop(UUID ownerUserId, String section);

    /**
     * Records the items on the list at {@code at}: each one already known is seen again; the others are added.
     *
     * @return the items added, in the order of {@code keys}
     */
    List<SeenItem> see(UUID ownerUserId, String section, Collection<String> keys, Instant at);

    /** Deletes the section's items not seen at the look made at {@code lookedAt}: off a list read whole. */
    int forgetUnseen(UUID ownerUserId, String section, Instant lookedAt);

    /** The user's items not yet dealt with, oldest first: a look that stopped half way left them. */
    List<SeenItem> pending(UUID ownerUserId);

    /** The items were posted, or set aside unposted. */
    void dealtWith(Collection<UUID> itemIds, Instant at);

    /** The items a proactive conversation was opened about. */
    void analysed(Collection<UUID> itemIds, UUID conversationId, Instant at);

    /** The items were not asked about after all: the conversation opened for them was refused and deleted. */
    void notAnalysed(Collection<UUID> itemIds);

    /** Drops the states, and their items, not looked at since {@code cutoff}, at most {@code limit}. */
    int deleteNotLookedAtSince(Instant cutoff, int limit);
}
