package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: cycle counts ({@code inventory.cycle_count}, {@code cycle_count_line}) as far as this module writes them so
 * far — the recount a short pick asks for (docs 04c X-SHORT step 03) — and reads them for the screen. Counting,
 * reconciling and posting the difference are the cycle-count story's, not yet built.
 */
public interface Recounts {

    /** One recount and its lines, as stored. */
    record Recount(UUID id, String number, UUID warehouseId, String triggerType, String status, boolean blind,
                   LocalDate plannedDate, String note, Instant createdAt, List<Line> lines) {
        public Recount {
            lines = List.copyOf(lines);
        }
    }

    /** What to count where; {@code expectedQty} is what the books say, null where a blind counter must not see it. */
    record Line(UUID id, String locationCode, String sku, String lotNumber, Integer expectedQty, Integer countedQty) { }

    /**
     * An open (PLANNED or IN_PROGRESS) recount with a line for this bin, SKU and lot, if any. Holds the slot until
     * the transaction ends, so a caller that finds none can open one without a second caller doing the same.
     */
    Optional<Recount> findOpenFor(LocationId location, Sku sku, String lotNumber);

    /** A new blind SHORT_PICK recount, PLANNED for {@code day}, with one line. */
    Recount openShortPick(UUID warehouseId, LocationId location, Sku sku, String lotNumber, int expectedQty,
                          String note, LocalDate day);

    /** Recounts, newest first, optionally of one status and limited to warehouses. */
    List<Recount> find(String status, java.util.Set<UUID> warehouses, long offset, int limit);

    long count(String status, java.util.Set<UUID> warehouses);

    /** A sellable layer whose free units an open SHORT_PICK recount puts in doubt. */
    record Frozen(UUID stockItemId, String sku, String locationCode, long free) { }

    /**
     * The layers of these SKUs that are sellable on {@code today} (AVAILABLE, not expired, something unreserved) and
     * sit in a bin, SKU and lot an open SHORT_PICK recount covers. A picker found fewer units there than the books
     * hold, so until the count closes none of the free ones is promised or allocated (docs 04c X-SHORT step 03).
     */
    List<Frozen> frozen(java.util.Collection<String> skus, LocalDate today);

    /** The recount, its row locked until the transaction ends. */
    Optional<Recount> findForUpdate(UUID id);

    /** CANCELLED, with the reason appended to the note. */
    void cancel(UUID id, String reason, String actor);
}
