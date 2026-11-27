package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the module that owns goods receipts which receipt each receipt line is on, for the carton list (#454): a carton
 * records only the line it was labelled on, and a bare line id can be neither shown nor linked. Implemented by
 * {@code procurement}; see the package documentation for why the interface lives here.
 */
public interface ReceiptLineReceipts {

    /**
     * Runs in the caller's transaction. One question for a whole page, never one per carton.
     *
     * @return each of {@code receiptLineIds} that exists, mapped to its receipt; an unknown line is absent
     */
    Map<UUID, Receipt> receiptsOf(Collection<UUID> receiptLineIds);

    /** A goods receipt as a screen names and links it. */
    record Receipt(UUID id, String number) {
    }
}
