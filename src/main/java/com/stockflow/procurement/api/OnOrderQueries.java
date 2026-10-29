package com.stockflow.procurement.api;

import java.util.Collection;
import java.util.List;

/**
 * What is already bought and not yet received, per SKU, for a reorder suggestion (ADR-0010). A planner who does not
 * see the open purchase orders orders the same goods twice, and keeps doing it every day until the first lot is put
 * away (InventoryPolicy: "a suggested purchase is maxQty minus what is on hand and on order").
 *
 * <p>Company-wide, like available-to-promise, and in base units: a line entered in a packing unit stores its
 * base-unit quantity ({@code ordered_qty = unit_qty × factor}, V20261020000400). Standard purchase orders only: a
 * subcontract order buys printing for one production order, and its goods are spoken for.</p>
 *
 * <p><b>The supplier's answer counts, lateness is reported.</b> A refusal is recorded beside the order's status and
 * does not move it: the order stays CONFIRMED until somebody cancels it, and receiving refuses it. Counted as on order,
 * those units would hold back the very purchase that replaces them, so they are reported apart. A late order is
 * different: it usually still arrives, so its units stay on order and are only flagged as overdue.</p>
 */
public interface OnOrderQueries {

    /** The most SKUs one call answers for. */
    int MAX_SKUS = 100;

    /**
     * Every SKU of {@code skus} once, in the order given, with zeros when nothing is open.
     *
     * @param skus SKU codes as stored (upper case), at most {@value #MAX_SKUS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for more than
     *         {@value #MAX_SKUS} SKUs
     */
    List<SkuOnOrder> onOrder(Collection<String> skus);
}
