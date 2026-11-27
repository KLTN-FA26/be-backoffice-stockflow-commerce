package com.stockflow.order.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * How much of an order of a signed contract is held at its shipping warehouse, per line and per delivery batch, as the
 * order's stock coverage screen shows it ({@code GET /api/v1/orders/{orderId}/stock-coverage}, 4B-7, kltn-docs 17 §4.2
 * step 3).
 *
 * <p>Published for the back-office assistant (ADR-0014), whose answer to "why has this order not moved?" needs the one
 * fact no other {@code api} gives: an order is released one delivery batch at a time, and only once every carton of
 * that batch is held at the shipping warehouse (17 BR-29). The screen's record is internal; this is the same figures,
 * named.</p>
 *
 * <p><b>Scope.</b> The screen's: every customer's order, from the back office, under
 * {@code sales-order-shipping-warehouses:READ} with scope ALL. Read only; nothing is held or released here.</p>
 */
public interface OrderStockCoverage {

    /**
     * The order's stock at its shipping warehouse, the earliest delivery batch served first; empty for an unknown
     * order and for one whose contract is not signed yet, which holds nothing and has no shipping warehouse to choose.
     */
    Optional<Coverage> coverage(UUID orderId);

    /**
     * @param warehouseChosen Sales chose the shipping warehouse; until they do, nothing is held for the order
     * @param warehouseCode   the shipping warehouse's code; null while none is chosen
     * @param fullyHeld       every carton of every line is held (or already picked for the order)
     * @param lines           per order line, across every batch
     * @param batches         per delivery batch of the contract's schedule, earliest first; empty for an order
     *                        without one, which is released whole
     * @param releasableBatch the seq of the batch the warehouse may release next once it is held in full (17 BR-29):
     *                        the earliest not released yet, whose batch before it was handed over in full; null for an
     *                        order without a schedule, one whose batches are all released, and one whose batch before
     *                        the next still has goods to hand over. With {@link Batch#fullyHeld} it says whether the
     *                        order waits for the warehouse to release it - the one step no status names.
     */
    record Coverage(boolean warehouseChosen, String warehouseCode, boolean fullyHeld, List<Line> lines,
                    List<Batch> batches, Integer releasableBatch) {

        public Coverage {
            lines = lines == null ? List.of() : List.copyOf(lines);
            batches = batches == null ? List.of() : List.copyOf(batches);
        }

        /** A coverage that does not say which batch is released next. */
        public Coverage(boolean warehouseChosen, String warehouseCode, boolean fullyHeld, List<Line> lines,
                        List<Batch> batches) {
            this(warehouseChosen, warehouseCode, fullyHeld, lines, batches, null);
        }
    }

    /**
     * One delivery batch: its window in force (17 BR-28) and its lines' cover. A batch picked or on its way counts as
     * held: what was picked for it is no longer short.
     */
    record Batch(int seq, LocalDate windowStart, LocalDate windowEnd, boolean fullyHeld, List<Line> lines) {

        public Batch {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** An order line's cartons: needed, held (or already picked), and short. */
    record Line(String sku, int cartons, int heldCartons, int shortCartons) {
    }
}
