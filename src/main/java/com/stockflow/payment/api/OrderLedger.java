package com.stockflow.payment.api;

import java.math.BigDecimal;

/**
 * An order's money as payment's ledger holds it (G2-3), read synchronously: what the order works out at the handover
 * (whether the balance is in) from this rather than from the money it has heard of by events, which may be a moment
 * behind.
 *
 * @param collected net allocations (direct and through receivables) plus payments recorded before the ledger, less the
 *                  refunds asked for
 * @param paidAhead the part of {@code collected} not paid against a receivable. No rule reads it since COD went
 *                  (V20261108000400); it goes with the ledger, which the contract's instalments replace
 */
public record OrderLedger(BigDecimal collected, BigDecimal paidAhead) {
}
