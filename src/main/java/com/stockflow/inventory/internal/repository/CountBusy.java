package com.stockflow.inventory.internal.repository;

/**
 * What "a count holds this carton" means, in SQL, in one place (SCRUM-146; kltn-docs 20 BR-10). The slot claim of a
 * plan, a found line and a short pick read {@link #BUSY}; so does the blind-count masking of the stock screens
 * ({@link #stockRowInBusySlot}, {@link #movementInBusySlot}), which hides the books of exactly the cartons the claim
 * refuses to count twice. {@code UnderCountSpecs} states the same rule for the JPA criteria queries of the ledger and
 * the adjustments, and {@code BlindCountIntegrationTest} pins that the two agree.
 *
 * <p>A slot is one LPN carton ({@code CountSlot}, team plan 4C-3): a stock row, a ledger line or an adjustment belongs
 * to the carton it names. A loose slot (bin, SKU and lot) is only a line of a count written before counts were by
 * carton, matched as it was so its history stays masked while its variance waits for approval.</p>
 *
 * <p>A carton with an adjustment asked by hand still waiting for approval is busy for a count as well
 * ({@link #PENDING_ADJUSTMENT}, read by the claim): the count's variance would be a second pending adjustment of the
 * same carton ({@code uk_stock_adjustment_pending_package}), deciding it twice.</p>
 */
final class CountBusy {

    /**
     * When line {@code l} of count {@code c} holds its slot: the count is open, or it is submitted and a
     * {@code COUNT_VARIANCE} adjustment of the line still waits for approval. One fragment for {@code claim} (plans,
     * found lines, recounts) and {@code findHolding} (short picks), so the two can never disagree on what busy is.
     */
    static final String BUSY = """
            (c.status IN ('PLANNED', 'IN_PROGRESS')
             OR (c.status = 'COMPLETED'
                 AND EXISTS (SELECT 1 FROM inventory.stock_adjustment a
                              WHERE a.cycle_count_line_id = l.id
                                AND a.reason_code = 'COUNT_VARIANCE'
                                AND a.status = 'PENDING_APPROVAL')))""";

    /**
     * When adjustment {@code a} holds its carton for a count: asked by hand (no count line) and still waiting for
     * approval. A count's own pending variance is {@link #BUSY}'s, through its line.
     */
    static final String PENDING_ADJUSTMENT = """
            (a.status = 'PENDING_APPROVAL' AND a.cycle_count_line_id IS NULL AND a.stock_package_id IS NOT NULL)""";

    private CountBusy() {
    }

    /**
     * True when the stock row {@code alias} (a {@code inventory.stock_item}) lies in a busy slot: a carton row in its
     * carton's slot, a loose row in the slot of its bin, SKU and lot.
     */
    static String stockRowInBusySlot(String alias) {
        return """
                EXISTS (SELECT 1 FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                         WHERE %1$s AND l.sku = %2$s.sku
                           AND ((l.stock_package_id IS NOT NULL AND l.stock_package_id = %2$s.stock_package_id)
                                OR (l.stock_package_id IS NULL AND %2$s.stock_package_id IS NULL
                                    AND l.location_code = %2$s.location_code
                                    AND COALESCE(btrim(l.lot_number), '') = COALESCE(btrim(%2$s.lot_number), ''))))"""
                .formatted(BUSY, alias);
    }

    /**
     * True when the ledger line {@code alias} (a {@code inventory.stock_movement}) touches a busy slot on either
     * side. A loose slot matches by bin on either side whatever the line's carton columns say: over-hiding the move of
     * a carton through a bin whose loose stock is being counted is the safe side of a rule about what a counter may
     * infer.
     */
    static String movementInBusySlot(String alias) {
        return """
                EXISTS (SELECT 1 FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                         WHERE %1$s AND l.sku = %2$s.sku
                           AND ((l.stock_package_id IS NOT NULL
                                 AND l.stock_package_id IN (%2$s.from_stock_package_id, %2$s.to_stock_package_id))
                                OR (l.stock_package_id IS NULL
                                    AND l.location_code IN (%2$s.from_location_code, %2$s.to_location_code)
                                    AND COALESCE(btrim(l.lot_number), '') = COALESCE(btrim(%2$s.lot_number), ''))))"""
                .formatted(BUSY, alias);
    }
}
