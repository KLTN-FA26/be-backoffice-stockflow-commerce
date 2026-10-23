package com.stockflow.inventory.internal.domain;

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

    /** Takes the slot's advisory lock (seed 703) and returns the open count holding it, if any. */
    Optional<CycleCount> findOpenFor(CountSlot slot);

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

    List<CycleCount> find(CycleCountStatus status, Set<UUID> warehouses, long offset, int limit);

    long count(CycleCountStatus status, Set<UUID> warehouses);

    Map<UUID, List<LineAdjustment>> adjustmentsOf(Collection<UUID> lineIds);

    Map<UUID, String> lpnsOf(Collection<StockPackageId> ids);

    List<Frozen> frozen(Collection<String> skus, LocalDate today);

    /** A variance adjustment raised from a count line. */
    record LineAdjustment(UUID id, String number, int quantityDelta, String status) { }

    /** Stock held back from picking by an open SHORT_PICK recount. */
    record Frozen(UUID stockItemId, String sku, String locationCode, long free) { }
}
