package com.stockflow.inventory.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Stock in, out and on hand per SKU and warehouse over a period, summed from the stock ledger (kltn-docs 20 §4.7;
 * 2026-11-14 plan, BE2-C) - what the reporting module lays out as the stock summary ("xuất–nhập–tồn").
 *
 * <p>Every change of stock writes one {@code inventory.stock_movement} line (20 BR-16), naming the location the pieces
 * left and the one they reached. A warehouse is the prefix of its location codes (06 BR-13), so a line changes a
 * warehouse W by {@code quantity × ([to in W] − [from in W])}: a receipt or a return is +q, a pick −q, an adjustment
 * ±q, and a move inside W - putaway, a carton move, a status change, a pick to the staging area - is zero without
 * being listed. Opening is the sum of that before the period, closing the sum up to its end; there is no stored
 * balance to drift from the ledger.</p>
 *
 * <p>Its own interface rather than more of {@link InventoryService}: a read for one report.</p>
 */
public interface StockLedgerTotals {

    /**
     * The totals of every (warehouse, SKU) the ledger has a line for that changed the warehouse, before the end of the
     * period - one statement over the ledger. A pair whose every column is zero (stock that came and went before the
     * period) is included; the caller decides whether to show it.
     *
     * <p>The days are business days (Asia/Ho_Chi_Minh): the period runs from 00:00 of {@code from} to 00:00 of the day
     * after {@code to}, Vietnam time.</p>
     *
     * <p>A caller limited to warehouses (scope WAREHOUSE, {@code WarehouseScope}) gets only theirs, whatever it asks.</p>
     *
     * @param warehousePrefixesOrNull only these warehouses; null for every one
     * @param skusOrNull              only these SKUs; null for every one, empty for none
     */
    List<StockMovementTotals> movementTotals(LocalDate from, LocalDate to, Collection<String> warehousePrefixesOrNull,
                                             Collection<String> skusOrNull);
}
