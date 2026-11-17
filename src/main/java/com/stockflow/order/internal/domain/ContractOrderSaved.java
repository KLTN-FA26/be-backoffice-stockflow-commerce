package com.stockflow.order.internal.domain;

import java.util.UUID;

/**
 * An order of a contract was written (4A-7): confirmed (signed, its installment on signing paid, its credit approved),
 * its shipping warehouse chosen, stock held or topped up for it, released, picked, handed over, put on hold, cancelled,
 * its remainder closed. An application event inside the order module, published by the repository on every save, so
 * whatever path changes what an order of a contract holds or owes - and so its delay alerts (kltn-docs 17 §4.12 step
 * 48) - is followed without anyone remembering to. Its listener recomputes after the commit.
 */
public record ContractOrderSaved(UUID salesOrderId) {
}
