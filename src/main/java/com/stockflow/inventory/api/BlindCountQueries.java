package com.stockflow.inventory.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which stock rows' books the caller may not read because a cycle count holds their slot (kltn-docs 20 BR-07,
 * SCRUM-146): the rule the stock screens apply, for another module that shows the same rows
 * ({@link InventoryService#availabilityOf}) to a person - the assistant (ADR-0011).
 *
 * <p>{@code availabilityOf} stays a plain read with every figure, because releases and holds plan over it with no
 * person behind them. A module showing its rows to someone asks here which of them to show without quantities, as
 * {@code GET /api/v1/inventory/stock-items} does: the row stays, its figures do not, and a hidden figure is said to be
 * hidden during the count, never shown as zero.</p>
 *
 * <p><b>Who is blind</b> is decided here, from the caller in the security context, by the screens' own rule: whoever
 * lacks {@code inventory-cycle-counts:APPROVE} - and a missing caller, so the books never reach whoever counts them.
 * Call it inside the guard of the screen being mirrored.</p>
 */
public interface BlindCountQueries {

    /**
     * Of {@code stockPackageIds} ({@link StockAvailability#stockItemId()}), those whose quantities the caller may not see
     * now: their slot is under a count, open or with its variance still pending approval. Empty for a caller who may
     * decide counts, and for an empty argument.
     */
    Set<UUID> hiddenFromCaller(Collection<UUID> stockPackageIds);
}
