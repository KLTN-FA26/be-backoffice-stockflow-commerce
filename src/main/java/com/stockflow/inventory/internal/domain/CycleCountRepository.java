package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.stockflow.inventory.internal.domain.CycleCount.CountLineDraft;

/**
 * Port of the {@link CycleCount} aggregate. Beside load and save it carries the queries a count needs
 * that are not about one aggregate: slot claims, scope generation, book quantities and the SHORT_PICK
 * freeze. They stay here, next to the aggregate they serve, rather than in a second port.
 */
public interface CycleCountRepository {

    Optional<CycleCount> findById(UUID id);

    /** Locks the count row: every count, submit and cancel starts here so they serialise per count. */
    Optional<CycleCount> findByIdForUpdate(UUID id);

    CycleCount save(CycleCount count);

    String nextNumber(LocalDate day);

    /**
     * Takes the slot's advisory lock (seed 703) and returns the count holding it, if any: held as {@link #claim} means
     * busy - on an open count, or on a completed one whose variance adjustment is still pending approval.
     */
    Optional<CycleCount> findHolding(CountSlot slot);

    /**
     * Locks all slots in sorted key order; returns each busy slot with the number of the count holding it. Busy: on
     * an open count, or on a completed one whose variance adjustment is still pending approval.
     */
    Map<CountSlot, String> claim(Collection<CountSlot> slots);

    /** The stocked slots a scope selects, with their book quantities. */
    List<CountLineDraft> stockIn(String warehousePrefix, Collection<String> locationCodes,
                                 Collection<String> locationPrefixes, Collection<String> skus);

    /** Committed on_hand, read FOR SHARE. */
    int bookQuantity(CountSlot slot);

    /**
     * A page of counts, newest first, as headers with their line tallies. No line is loaded: a list of counts must
     * not grow with the lines on them.
     */
    List<Header> findHeaders(CycleCountStatus status, Set<UUID> warehouses, long offset, int limit);

    long count(CycleCountStatus status, Set<UUID> warehouses);

    Map<UUID, List<LineAdjustment>> adjustmentsOf(Collection<UUID> lineIds);

    Map<UUID, String> lpnsOf(Collection<StockPackageId> ids);

    /** Who entered the line's figure; empty when the line is unknown or was never counted. */
    Optional<UUID> countedByOf(UUID lineId);

    List<Frozen> frozen(Collection<String> skus, LocalDate today);

    /** A variance adjustment raised from a count line. */
    record LineAdjustment(UUID id, String number, int quantityDelta, String status) { }

    /**
     * Stock held back from picking: by an open SHORT_PICK recount (or one whose variance waits for approval), or by a
     * pending shortage of any count.
     */
    record Frozen(UUID stockItemId, String sku, String locationCode, long free) { }

    /**
     * A count without its lines: {@code lineCount} lines, {@code countedCount} of them counted, {@code varianceCount}
     * of those counted differently from the books (a figure only who decides counts may see).
     */
    record Header(UUID id, String number, UUID warehouseId, CycleCountTrigger trigger, CycleCountStatus status,
                  boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt, Instant completedAt,
                  String note, Instant createdAt, int lineCount, int countedCount, int varianceCount) { }
}
