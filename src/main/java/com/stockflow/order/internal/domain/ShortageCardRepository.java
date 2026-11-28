package com.stockflow.order.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link ShortageCard} (kltn-docs 17 §4.14). Every write is made under the order's row lock, which
 * the caller takes first (lock order: the order, then its cards - BE-1; later pull requests add the pick tasks before the
 * order).
 */
public interface ShortageCardRepository {

    /** Inserts a new card, or updates one read before: a stale copy is refused ({@code OptimisticLockingFailureException}). */
    ShortageCard save(ShortageCard card);

    /**
     * Records the chat message the card was posted as. Not a change of the card - the version a customer's choice names
     * stays - and not part of {@link #save}, so a stale copy cannot wipe it.
     */
    void linkMessage(UUID cardId, UUID messageId);

    Optional<ShortageCard> find(UUID id);

    /**
     * The next card code of {@code day} (the Vietnam day it is sent), "TT-yyyyMMdd-NNNN" (UNH-CODE-1128): a per-day
     * counter taken in its own transaction, as order and RMA numbers are - a card refused after taking one leaves a gap,
     * never a duplicate.
     */
    String nextCode(java.time.LocalDate day);

    /** The card, locked {@code FOR UPDATE}. */
    Optional<ShortageCard> findForUpdate(UUID id);

    /** The order's card for a batch that is still live (SENT or ANSWERED): at most one. Not locked. */
    Optional<ShortageCard> liveOf(UUID orderId, UUID deliveryBatchId);

    /** Every live card of the order, whichever its batch. Not locked: a read may run in a read-only transaction. */
    List<ShortageCard> liveOf(UUID orderId);

    /** Every live card of the order, locked {@code FOR UPDATE}: for the operation that ends them. */
    List<ShortageCard> liveForUpdate(UUID orderId);

    /** The order's cards, newest first. */
    List<ShortageCard> ofOrder(UUID orderId);
}
