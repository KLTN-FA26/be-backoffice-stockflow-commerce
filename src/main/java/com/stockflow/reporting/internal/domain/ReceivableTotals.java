package com.stockflow.reporting.internal.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The column sums of the receivables report, one per currency: amounts of two currencies are never added. Every price
 * is in VND ({@code ck_customer_price_currency}), so in practice there is one, but the report does not assume it.
 * Credit limits are not summed - a limit is the customer's own, and a sum of them means nothing.
 */
public record ReceivableTotals(String currency, int customers, BigDecimal receivable, BigDecimal notYetDue,
                               BigDecimal overdue1To30, BigDecimal overdue31To60, BigDecimal overdue61To90,
                               BigDecimal overdueOver90, BigDecimal overdue, BigDecimal committedNotReached,
                               BigDecimal exposure) {

    private static ReceivableTotals zero(String currency) {
        BigDecimal z = BigDecimal.ZERO;
        return new ReceivableTotals(currency, 0, z, z, z, z, z, z, z, z, z);
    }

    private ReceivableTotals plus(ReceivableLine line) {
        return new ReceivableTotals(currency, customers + 1, receivable.add(line.receivable()),
                notYetDue.add(line.notYetDue()), overdue1To30.add(line.overdue1To30()),
                overdue31To60.add(line.overdue31To60()), overdue61To90.add(line.overdue61To90()),
                overdueOver90.add(line.overdueOver90()), overdue.add(line.overdue()),
                committedNotReached.add(line.committedNotReached()), exposure.add(line.exposure()));
    }

    /** The sums of {@code lines} per currency, by currency code; none for no line. */
    public static List<ReceivableTotals> of(Collection<ReceivableLine> lines) {
        Map<String, ReceivableTotals> byCurrency = new TreeMap<>();
        for (ReceivableLine line : lines) {
            byCurrency.merge(line.currency(), zero(line.currency()).plus(line),
                    (sum, one) -> sum.plus(line));
        }
        return new ArrayList<>(byCurrency.values());
    }
}
