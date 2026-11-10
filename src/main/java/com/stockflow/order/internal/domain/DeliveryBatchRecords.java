package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 17 §4.5, BR-28, BR-29): what the order module records of its delivery batches once the contract is
 * signed. The schedule itself is the contract's and never changes (14 BR-14); these are the facts of working it:
 * which batch each hold stands for, which batches were released, and how their windows were moved.
 *
 * <p>Kept beside the order aggregate, not in it: holds and window changes are written by the services that take or
 * move them, under the order's row lock, and none of them changes what the order itself is.</p>
 */
public interface DeliveryBatchRecords {

    /** A hold of the order - a SKU reservation, or an allocation after release - and the batch it stands for. */
    record Hold(UUID reservationId, UUID batchId, UUID orderLineId) {
    }

    /** A batch released to the shipping warehouse. */
    record Release(UUID batchId, int seq, UUID warehouseId, Instant releasedAt, UUID releasedBy) {
    }

    /**
     * A window moved by Sales (BR-28): exactly one of the evidence file and the chat message is set.
     */
    record WindowChange(UUID id, UUID batchId, LocalDate oldStart, LocalDate oldEnd, LocalDate newStart,
                        LocalDate newEnd, WindowChangeReason reason, String note, String evidenceFileKey,
                        String evidenceFileName, UUID evidenceMessageId, UUID changedBy, Instant changedAt) {
    }

    List<Hold> holdsOf(UUID orderId);

    /** The holds were taken for {@code batchId}'s cartons of {@code orderLineId}. */
    void recordHolds(UUID orderId, UUID batchId, UUID orderLineId, Collection<UUID> reservationIds);

    /**
     * The holds that replaced {@code replaced} (its allocations at release, a short allocation's reallocation) stand
     * for the same batch; nothing when {@code replaced} stood for none.
     */
    void carry(UUID replaced, Collection<UUID> replacements);

    /** In schedule order. */
    List<Release> releasesOf(UUID orderId);

    void recordRelease(UUID orderId, Release release);

    /** Oldest first. */
    List<WindowChange> windowChangesOf(UUID orderId);

    void recordWindowChange(UUID orderId, WindowChange change);
}
