package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the receiving module which lot each goods receipt line is in, for the label of a carton still being counted
 * (SCRUM-473): its labels are printed while the receipt is a draft, before the carton has a stock row to carry the
 * lot, and the lot - the production order's code (kltn-docs 03 BR-03) - is on the receipt line. Implemented by
 * {@code procurement}; see the package documentation for why the interface lives here.
 */
public interface ReceiptLineLots {

    /**
     * Runs in the caller's transaction. One question for a whole label sheet, never one per carton.
     *
     * @return each of {@code receiptLineIds} that exists, mapped to its lot number; an unknown line is absent
     */
    Map<UUID, String> lotsOf(Collection<UUID> receiptLineIds);
}
