package com.stockflow.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount with a currency.
 *
 * <p>Every money field in this system is a {@code Money}, never a bare {@code BigDecimal} and
 * never a {@code double}. Two reasons, both of which have cost real projects real money:
 * a {@code double} cannot represent 0.1 exactly, and a bare {@code BigDecimal} lets you add
 * VND to USD without anything complaining.</p>
 *
 * <p>The system runs on VND only for now (OQ-09), but the currency is carried anyway so that
 * adding a second one later is a data change rather than a schema migration.</p>
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency VND = Currency.getInstance("VND");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }

    public static Money vnd(long amount) {
        return new Money(BigDecimal.valueOf(amount), VND);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine %s and %s".formatted(currency.getCurrencyCode(),
                            other.currency.getCurrencyCode()));
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
