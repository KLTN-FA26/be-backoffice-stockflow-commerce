package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order as the money ledger sees it (G2-3). Amounts are in {@code currency}; names of statuses and terms are the
 * order module's.
 *
 * @param status                the order's status, e.g. {@code PENDING_PAYMENT}, {@code CANCELLED}
 * @param paymentTerm           {@code PREPAID}, {@code DEPOSIT} or {@code CREDIT}
 * @param balancePolicy         how a deposit order's balance is collected ({@code BEFORE_DELIVERY}, {@code ON_DELIVERY},
 *                              {@code ON_CREDIT});
 *                              null for other terms
 * @param billable              what the order is worth to the customer now: its total less units closed out of it
 * @param depositRequired       null unless DEPOSIT
 * @param codInTransit          what carriers are collecting on delivery for goods handed over and not yet delivered
 *                              or returned (ON_DELIVERY): money the customer pays the carrier, not by transfer
 * @param confirmationThreshold the money a confirmed order must keep to stay confirmed - everything for PREPAID, the
 *                              deposit for DEPOSIT - while it is past PENDING_PAYMENT and before its goods leave; null
 *                              when taking money back cannot un-confirm it (pending, credit, shipped and later)
 * @param holdable              whether the order can be put on hold now (it is not already on hold, nor shipped)
 */
public record PayableOrder(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String status,
        String paymentTerm,
        String balancePolicy,
        String currency,
        BigDecimal billable,
        BigDecimal depositRequired,
        BigDecimal codInTransit,
        BigDecimal confirmationThreshold,
        boolean holdable
) {
}
