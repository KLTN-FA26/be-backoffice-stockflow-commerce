package com.stockflow.inventory.internal.repository;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which stock sits in a slot a cycle count holds (open, or submitted with its variance still pending approval): the
 * rows whose book quantity a counter must not read (SCRUM-146, owner decision of 28/10). A read, no lock: the claim
 * that keeps two counts off one slot is {@code CycleCountRepository.claim}; this only answers "is it held right now".
 */
public interface SlotsUnderCount {

    /** The ids, among these stock rows, of those that lie in a held slot. */
    Set<UUID> stockItemsUnderCount(Collection<UUID> stockItemIds);

    /** Whether this adjustment is about a slot a count holds (a pending variance of a line is, by definition). */
    boolean adjustmentUnderCount(UUID adjustmentId);

    /** The locations where a row of this lot (stock lot) lies in a held slot. */
    Set<String> locationsUnderCount(UUID lotId);
}
